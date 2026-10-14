package dev.wendelin.hausfunk.media

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import dev.wendelin.hausfunk.SerialExecutor
import dev.wendelin.hausfunk.data.DeviceName
import dev.wendelin.hausfunk.discovery.BabyAdvertiser
import dev.wendelin.hausfunk.signaling.Answer
import dev.wendelin.hausfunk.signaling.Busy
import dev.wendelin.hausfunk.signaling.Bye
import dev.wendelin.hausfunk.signaling.Hello
import dev.wendelin.hausfunk.signaling.Ice
import dev.wendelin.hausfunk.signaling.Offer
import dev.wendelin.hausfunk.signaling.PROTOCOL_VERSION
import dev.wendelin.hausfunk.signaling.SignalingConnection
import dev.wendelin.hausfunk.signaling.SignalingMessage
import dev.wendelin.hausfunk.signaling.SignalingServer
import dev.wendelin.hausfunk.signaling.SignalingTransport
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

data class BabyState(
    /** Name of the connected parent, null while waiting. */
    val parentName: String? = null,
    /** False if the camera could not be opened; sound still works. */
    val cameraAvailable: Boolean = true,
    val failed: Boolean = false,
)

/**
 * Baby side: captures camera and microphone once and streams them to one parent (phase 2;
 * several parents arrive in phase 6). Owns every WebRTC object; all of them are touched only on
 * the session's own thread. [stop] is final; create a new session to start again.
 */
class BabySession(context: Context, private val transport: SignalingTransport) {
    private val context = context.applicationContext
    private val executor = SerialExecutor("baby-session")
    private val _state = MutableStateFlow(BabyState())
    val state: StateFlow<BabyState> = _state.asStateFlow()

    private lateinit var eglBase: EglBase
    private lateinit var audioModule: JavaAudioDeviceModule
    private lateinit var factory: PeerConnectionFactory
    private lateinit var audioSource: AudioSource
    private lateinit var audioTrack: AudioTrack
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private val advertiser = BabyAdvertiser(this.context)
    private var server: SignalingServer? = null
    private var peer: Peer? = null
    private var stopped = false
    private lateinit var ownHello: Hello

    fun start(name: String, deviceId: String, appVersion: String) = executor.execute {
        if (stopped) return@execute
        ownHello = Hello(PROTOCOL_VERSION, deviceId, name, appVersion)
        try {
            WebRtc.initialize(context)
            createMedia()
            val s = transport.listen()
            server = s
            advertiser.start(name, deviceId, s.port)
            thread(name = "baby-accept") { acceptLoop(s) }
        } catch (e: IOException) {
            Log.w(TAG, "could not start", e)
            _state.value = _state.value.copy(failed = true)
        } catch (e: RuntimeException) {
            // Camera or audio hardware refused; an exception here would kill the app.
            Log.w(TAG, "could not start", e)
            _state.value = _state.value.copy(failed = true)
        }
    }

    fun stop() = executor.execute {
        if (stopped) return@execute
        stopped = true
        advertiser.stop()
        server?.close()
        peer?.let { close(it, sendBye = true) }
        disposeMedia()
        executor.shutdown()
    }

    private fun createMedia() {
        eglBase = EglBase.create()
        audioModule = JavaAudioDeviceModule.builder(context)
            .setAudioSource(captureSource())
            // Quiet sounds must not be swallowed by processing meant for calls.
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val constraints = MediaConstraints().apply {
            for (key in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter")) {
                mandatory += MediaConstraints.KeyValuePair(key, "false")
            }
        }
        audioSource = factory.createAudioSource(constraints)
        audioTrack = factory.createAudioTrack("audio", audioSource)

        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val cameraName = names.firstOrNull { enumerator.isBackFacing(it) } ?: names.firstOrNull()
        val cam = cameraName?.let { enumerator.createCapturer(it, null) }
        if (cam == null) {
            _state.value = _state.value.copy(cameraAvailable = false)
            return
        }
        val helper = SurfaceTextureHelper.create("capture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        cam.initialize(helper, context, source.capturerObserver)
        cam.startCapture(WIDTH, HEIGHT, FPS)
        capturer = cam
        textureHelper = helper
        videoSource = source
        videoTrack = factory.createVideoTrack("video", source)
    }

    private fun captureSource(): Int {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun disposeMedia() {
        if (!::eglBase.isInitialized) return // never started
        try {
            capturer?.stopCapture()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        capturer?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        textureHelper?.dispose()
        if (::audioTrack.isInitialized) audioTrack.dispose()
        if (::audioSource.isInitialized) audioSource.dispose()
        if (::factory.isInitialized) factory.dispose()
        if (::audioModule.isInitialized) audioModule.release()
        eglBase.release()
    }

    private fun acceptLoop(s: SignalingServer) {
        while (true) {
            val connection = try {
                s.accept()
            } catch (e: IOException) {
                return // server closed
            }
            executor.execute { onIncoming(connection) }
        }
    }

    private fun onIncoming(connection: SignalingConnection) {
        if (stopped || peer != null) {
            // Phase 2 serves one parent at a time.
            thread(name = "baby-busy") {
                try {
                    connection.send(Busy)
                } catch (e: IOException) {
                    // Nothing to tell a parent that is already gone.
                }
                connection.close()
            }
            return
        }
        val p = Peer(connection)
        peer = p
        thread(name = "baby-read") { readLoop(p) }
    }

    private fun readLoop(p: Peer) {
        try {
            while (true) {
                val message = p.connection.receive() ?: break
                if (message is Answer) p.connection.setReadTimeout(0) // set up; may idle from now on
                executor.execute { if (peer === p) handle(p, message) }
            }
        } catch (e: IOException) {
            Log.i(TAG, "parent connection ended: ${e.javaClass.simpleName}")
        }
        executor.execute { if (peer === p) close(p, sendBye = false) }
    }

    private fun handle(p: Peer, message: SignalingMessage) {
        when (message) {
            is Hello -> onHello(p, message)
            is Answer -> p.pc?.let { pc ->
                pc.setRemoteDescription(
                    WebRtc.sdpObserver(executor, { fail(p, it) }) {
                        if (peer === p) {
                            p.ice.flush(pc)
                            limitVideo(p)
                        }
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, message.sdp),
                )
            }
            is Ice -> p.pc?.let { pc -> p.ice.add(pc, IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)) }
            Bye -> close(p, sendBye = false)
            is Offer, Busy -> fail(p, "unexpected ${message.javaClass.simpleName}")
        }
    }

    private fun onHello(p: Peer, hello: Hello) {
        if (p.pc != null || hello.version != PROTOCOL_VERSION) return fail(p, "bad hello")
        p.name = DeviceName.sanitize(hello.name)
        if (!send(p, ownHello)) return
        val pc = factory.createPeerConnection(WebRtc.rtcConfiguration(), observer(p)) ?: return fail(p, "no peer connection")
        p.pc = pc
        val sendOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(STREAM_ID))
        pc.addTransceiver(audioTrack, sendOnly)
        videoTrack?.let { track ->
            val video = pc.addTransceiver(track, sendOnly)
            video.setCodecPreferences(preferredVideoCodecs())
            p.video = video
        }
        p.control = pc.createDataChannel("control", DataChannel.Init().apply { ordered = true })
        pc.createOffer(
            WebRtc.sdpObserver(executor, { fail(p, it) }) { offer ->
                if (peer !== p || offer == null) return@sdpObserver
                pc.setLocalDescription(
                    WebRtc.sdpObserver(executor, { fail(p, it) }) {
                        if (peer === p) send(p, Offer(offer.description))
                    },
                    offer,
                )
            },
            MediaConstraints(),
        )
        _state.value = _state.value.copy(parentName = p.name)
    }

    /** Hardware H.264 where the phone has it, otherwise VP8. */
    private fun preferredVideoCodecs(): List<org.webrtc.RtpCapabilities.CodecCapability> {
        val hardwareH264 = HardwareVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            .supportedCodecs.any { it.name.equals("H264", ignoreCase = true) }
        val preferred = if (hardwareH264) "H264" else "VP8"
        return factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            .sortedBy { if (it.name.equals(preferred, ignoreCase = true)) 0 else 1 }
    }

    private fun limitVideo(p: Peer) {
        val sender = p.video?.sender ?: return
        val parameters = sender.parameters
        parameters.encodings.forEach {
            it.maxBitrateBps = MAX_BITRATE_BPS
            it.maxFramerate = FPS
        }
        sender.parameters = parameters
    }

    private fun observer(p: Peer) = object : PeerObserver() {
        override fun onIceCandidate(candidate: IceCandidate) = executor.execute {
            if (peer === p) send(p, Ice(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.DISCONNECTED
            ) {
                executor.execute { if (peer === p) close(p, sendBye = true) }
            }
        }
    }

    private fun send(p: Peer, message: SignalingMessage): Boolean = try {
        p.connection.send(message)
        true
    } catch (e: IOException) {
        close(p, sendBye = false)
        false
    }

    private fun fail(p: Peer, reason: String) {
        Log.w(TAG, "closing parent connection: $reason")
        if (peer === p) close(p, sendBye = true)
    }

    private fun close(p: Peer, sendBye: Boolean) {
        if (peer === p) peer = null
        if (sendBye) {
            try {
                p.connection.send(Bye)
            } catch (e: IOException) {
                // Already gone.
            }
        }
        p.connection.close()
        p.control?.dispose()
        p.pc?.dispose()
        p.pc = null
        _state.value = _state.value.copy(parentName = null)
    }

    private class Peer(val connection: SignalingConnection) {
        var name: String = ""
        var pc: PeerConnection? = null
        var video: RtpTransceiver? = null
        var control: DataChannel? = null
        val ice = PendingIce()
    }

    private companion object {
        const val TAG = "BabySession"
        const val STREAM_ID = "hausfunk"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 15
        const val MAX_BITRATE_BPS = 1_500_000
    }
}
