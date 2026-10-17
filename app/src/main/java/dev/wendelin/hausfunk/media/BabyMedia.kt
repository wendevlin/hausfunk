package dev.wendelin.hausfunk.media

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Baby side capture: one factory, the camera and the microphone, shared by every parent, plus the
 * sound level of the raw capture. Used on the session's thread; [onCameraWorking] is called on
 * the camera's thread.
 */
class BabyMedia(
    context: Context,
    private val eglBase: EglBase,
    /** True once frames arrive, false when the camera fails or is taken away. */
    private val onCameraWorking: (Boolean) -> Unit,
) {
    private val context = context.applicationContext
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private val previewSinks = mutableSetOf<VideoSink>()

    lateinit var factory: PeerConnectionFactory
        private set
    lateinit var audioTrack: AudioTrack
        private set

    /** Null on a phone without a camera; sound still works. */
    var videoTrack: VideoTrack? = null
        private set
    var canSwitchCamera = false
        private set

    /** Hardware H.264 where the phone has it, otherwise VP8; worked out once. */
    lateinit var videoCodecs: List<RtpCapabilities.CodecCapability>
        private set

    /** The loudest 100 ms of the raw capture since the last [takeLevel]. */
    @Volatile private var levelMeter: LevelMeter? = null

    /** Opens camera and microphone. Throws if the hardware refuses; call [dispose] then. */
    fun start() {
        val module = JavaAudioDeviceModule.builder(context)
            .setAudioSource(captureSource())
            // Quiet sounds must not be swallowed by processing meant for calls.
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            // The raw capture, before any processing, measures the level parents see.
            .setSamplesReadyCallback { samples ->
                val meter = levelMeter ?: LevelMeter(samples.sampleRate).also { levelMeter = it }
                meter.add(samples.data, samples.channelCount)
            }
            .createAudioDeviceModule()
        audioModule = module
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val constraints = MediaConstraints().apply {
            for (key in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")) {
                mandatory += MediaConstraints.KeyValuePair(key, "false")
            }
        }
        val source = factory.createAudioSource(constraints)
        audioSource = source
        audioTrack = factory.createAudioTrack("audio", source)
        videoCodecs = preferredVideoCodecs()

        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val cameraName = names.firstOrNull { enumerator.isBackFacing(it) } ?: names.firstOrNull()
        val cam = cameraName?.let { enumerator.createCapturer(it, cameraEvents) } ?: return
        val helper = SurfaceTextureHelper.create("capture", eglBase.eglBaseContext)
        textureHelper = helper
        val video = factory.createVideoSource(false)
        videoSource = video
        cam.initialize(helper, context, video.capturerObserver)
        capturer = cam
        // Capture starts with setCapturing, once someone wants to see the picture.
        videoTrack = factory.createVideoTrack("video", video).also { track -> previewSinks.forEach(track::addSink) }
        canSwitchCamera = names.size > 1
    }

    private var capturing = false
    private var reduced = false

    /**
     * Starting and stopping the camera happen here: stopping waits for a camera that is still
     * opening and could hang, which must never hold up the session (its pongs keep the parents'
     * alarm quiet).
     */
    private val cameraControl = Executors.newSingleThreadExecutor { Thread(it, "camera-control") }

    /** Whether the baby's own preview is shown (it needs the camera too). */
    val hasPreview: Boolean get() = previewSinks.isNotEmpty()

    /** Starts or stops the camera; nobody watching means no camera, less heat and battery. */
    fun setCapturing(on: Boolean) {
        val cam = capturer ?: return
        if (on == capturing) return
        capturing = on
        val reducedNow = reduced
        cameraControl.execute {
            if (on) {
                if (reducedNow) cam.startCapture(REDUCED_WIDTH, REDUCED_HEIGHT, REDUCED_FPS) else cam.startCapture(WIDTH, HEIGHT, FPS)
            } else {
                try {
                    cam.stopCapture()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    /**
     * Lower resolution and frame rate while the phone is hot; back to normal when it cooled down.
     * The camera itself captures less too, which is where most of the heat comes from.
     */
    fun setReduced(reduced: Boolean) {
        val source = videoSource ?: return
        this.reduced = reduced
        if (reduced) source.adaptOutputFormat(REDUCED_WIDTH, REDUCED_HEIGHT, REDUCED_FPS)
        else source.adaptOutputFormat(WIDTH, HEIGHT, FPS)
        val cam = capturer ?: return
        if (!capturing) return
        cameraControl.execute {
            if (reduced) cam.changeCaptureFormat(REDUCED_WIDTH, REDUCED_HEIGHT, REDUCED_FPS)
            else cam.changeCaptureFormat(WIDTH, HEIGHT, FPS)
        }
    }

    /** The camera stopped by itself (error, taken away): the next [setCapturing] opens it again. */
    fun onCameraStopped() {
        capturing = false
    }

    /** Front and back camera. */
    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun attachPreview(sink: VideoSink) {
        if (previewSinks.add(sink)) videoTrack?.addSink(sink)
    }

    fun detachPreview(sink: VideoSink) {
        if (previewSinks.remove(sink)) videoTrack?.removeSink(sink)
    }

    fun takeLevel(): Float? = levelMeter?.take()

    /** Releases whatever [start] opened, also after it failed halfway. */
    fun dispose() {
        setCapturing(false)
        // The camera is released after it stopped; a hanging camera is given up on after a while.
        val camera = cameraControl.submit {
            capturer?.dispose()
            videoTrack?.dispose()
            videoSource?.dispose()
            textureHelper?.dispose()
        }
        try {
            camera.get(CAMERA_RELEASE_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "camera release did not finish")
        }
        cameraControl.shutdown()
        if (::audioTrack.isInitialized) audioTrack.dispose()
        audioSource?.dispose()
        if (::factory.isInitialized) factory.dispose()
        audioModule?.release()
    }

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) = failed()
        override fun onCameraDisconnected() = failed()
        override fun onCameraFreezed(error: String?) = failed()
        override fun onCameraOpening(cameraName: String?) = Unit
        override fun onFirstFrameAvailable() = onCameraWorking(true)
        override fun onCameraClosed() = Unit

        private fun failed() {
            Log.w(TAG, "camera failed")
            onCameraWorking(false)
        }
    }

    private fun captureSource(): Int {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun preferredVideoCodecs(): List<RtpCapabilities.CodecCapability> {
        val hardwareH264 = HardwareVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            .supportedCodecs.any { it.name.equals("H264", ignoreCase = true) }
        val preferred = if (hardwareH264) "H264" else "VP8"
        return factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            .sortedBy { if (it.name.equals(preferred, ignoreCase = true)) 0 else 1 }
    }

    companion object {
        private const val TAG = "BabyMedia"
        private const val CAMERA_RELEASE_TIMEOUT_S = 5L

        // Captured at 1080p; sent at whatever the link and the encoder manage, scaled down
        // automatically.
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 15
        const val REDUCED_WIDTH = 640
        const val REDUCED_HEIGHT = 480
        const val REDUCED_FPS = 10
    }
}
