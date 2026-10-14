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
import dev.wendelin.hausfunk.signaling.TlsTransport
import dev.wendelin.hausfunk.identity.Fingerprint
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.identity.PeerClass
import dev.wendelin.hausfunk.pairing.PairedPeer
import dev.wendelin.hausfunk.pairing.PairingRunner
import android.os.SystemClock
import dev.wendelin.hausfunk.pairing.PairingState
import dev.wendelin.hausfunk.signaling.PairCommit
import org.webrtc.VideoSink
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
    /** False if the phone has no camera; sound still works. */
    val cameraAvailable: Boolean = true,
    /** The camera reported an error or was taken away (e.g. by another app). */
    val cameraError: Boolean = false,
    /** More than one camera: front and back can be switched. */
    val canSwitchCamera: Boolean = false,
    val failed: Boolean = false,
    /** A parent asking to pair, from its request until the user dismisses the result. */
    val pairing: PairingRunner? = null,
)

/**
 * Baby side: captures camera and microphone once and streams them to one paired parent (several
 * parents arrive in phase 6). An unknown parent may ask to pair at any time; nothing is stored
 * unless the user confirms the emojis on both phones. Owns every WebRTC object; all of them are
 * touched only on the session's own thread. [stop] is final; create a new session to start again.
 */
class BabySession(
    context: Context,
    private val identity: Identity,
    private val trustedIds: () -> Set<String>,
    /** Stores a newly paired parent. */
    private val onPaired: (PairedPeer) -> Unit,
) {
    private val transport = TlsTransport(identity, trustedIds)
    private val context = context.applicationContext
    private val executor = SerialExecutor("baby-session")
    private val _state = MutableStateFlow(BabyState())
    val state: StateFlow<BabyState> = _state.asStateFlow()

    /** Shared with the local preview renderer. */
    val eglBase: EglBase = EglBase.create()
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

    private var pairing: PairingRunner? = null
    private var pairingActive = false
    private var handshaking = 0
    private val previewSinks = mutableSetOf<VideoSink>()

    /** Devices whose last pairing attempt failed, until when they are ignored (elapsed ms). */
    private val pairingBlockedUntil = mutableMapOf<String, Long>()

    fun start(name: String, appVersion: String) = executor.execute {
        if (stopped) return@execute
        ownHello = Hello(PROTOCOL_VERSION, identity.deviceId, name, appVersion)
        try {
            WebRtc.initialize(context)
            createMedia()
            // Unknown parents may connect, but only to pair; see onPairingConnection.
            val s = transport.listen { true }
            server = s
            advertise()
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
        pairing?.cancel()
        advertiser.stop()
        server?.close()
        peer?.let { close(it, sendBye = true) }
        disposeMedia()
        executor.shutdown()
    }

    /** Hides the result of the last pairing. */
    fun dismissPairing() = executor.execute {
        if (!pairingActive) _state.value = _state.value.copy(pairing = null)
    }

    /** Call whenever the trust store changes: a removed parent is disconnected at once. */
    fun onTrustChanged(ids: Set<String>) = executor.execute {
        peer?.let { if (it.connection.peerDeviceId !in ids) close(it, sendBye = true) }
    }

    /** Rejects or ends a pairing in progress ("Passt nicht", back). */
    fun cancelPairing() = executor.execute { pairing?.cancel() }

    /** Front and back camera. */
    fun switchCamera() = executor.execute { capturer?.switchCamera(null) }

    /** Shows the camera picture in [sink] (the baby's own preview). Safe to call from any thread. */
    fun attachPreview(sink: VideoSink) = executor.execute {
        if (previewSinks.add(sink)) videoTrack?.addSink(sink)
    }

    fun detachPreview(sink: VideoSink) = executor.execute {
        if (previewSinks.remove(sink)) videoTrack?.removeSink(sink)
    }

    private fun advertise() {
        val s = server ?: return
        advertiser.stop()
        advertiser.start(ownHello.name, identity.deviceId, s.port)
    }

    private fun createMedia() {
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
        val cam = cameraName?.let { enumerator.createCapturer(it, cameraEvents) }
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
        videoTrack = factory.createVideoTrack("video", source).also { track -> previewSinks.forEach(track::addSink) }
        _state.value = _state.value.copy(canSwitchCamera = names.size > 1)
    }

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) = cameraFailed()
        override fun onCameraDisconnected() = cameraFailed()
        override fun onCameraFreezed(error: String?) = cameraFailed()
        override fun onCameraOpening(cameraName: String?) = Unit
        override fun onFirstFrameAvailable() {
            _state.value = _state.value.copy(cameraError = false)
        }
        override fun onCameraClosed() = Unit

        private fun cameraFailed() {
            Log.w(TAG, "camera failed")
            _state.value = _state.value.copy(cameraError = true)
        }
    }

    private fun captureSource(): Int {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun disposeMedia() {
        if (!::audioModule.isInitialized) {
            eglBase.release() // never started
            return
        }
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
        audioModule.release()
        eglBase.release()
    }

    private fun acceptLoop(s: SignalingServer) {
        while (true) {
            val pending = try {
                s.accept()
            } catch (e: IOException) {
                return // server closed
            }
            executor.execute { onAccepted(pending) }
        }
    }

    private fun onAccepted(pending: SignalingServer.Pending) {
        val open = handshaking + (if (peer != null) 1 else 0) + (if (pairingActive) 1 else 0)
        if (stopped || open >= MAX_CONNECTIONS) {
            pending.close()
            return
        }
        handshaking++
        thread(name = "baby-handshake") {
            val connection = try {
                pending.handshake()
            } catch (e: IOException) {
                Log.i(TAG, "handshake refused: ${e.javaClass.simpleName}")
                null
            }
            executor.execute {
                handshaking--
                when {
                    connection == null -> Unit
                    connection.peerClass == PeerClass.PAIRING -> onPairingConnection(connection)
                    else -> onIncoming(connection)
                }
            }
        }
    }

    private fun onPairingConnection(connection: SignalingConnection, firstMessage: SignalingMessage? = null) {
        // One pairing at a time; a device whose last attempt failed is ignored for a while, so
        // nobody on the Wi-Fi can keep popping up the pairing screen.
        val now = SystemClock.elapsedRealtime()
        pairingBlockedUntil.entries.removeAll { it.value <= now }
        if (stopped || pairingActive || connection.peerDeviceId in pairingBlockedUntil) {
            connection.close()
            return
        }
        pairingActive = true
        lateinit var runner: PairingRunner
        runner = PairingRunner(
            connection,
            isParent = false,
            ownDeviceId = identity.deviceId,
            ownName = ownHello.name,
            ownFingerprint = Fingerprint.sha256(identity.certificate.encoded),
            onPaired = onPaired,
            onFinished = {
                executor.execute {
                    if (pairing === runner) pairingActive = false
                    if (runner.state.value == PairingState.Failed) {
                        pairingBlockedUntil[connection.peerDeviceId] = SystemClock.elapsedRealtime() + PAIRING_BLOCK_MS
                    }
                }
            },
        )
        pairing = runner
        _state.value = _state.value.copy(pairing = runner)
        runner.start(firstMessage)
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
                if (message is PairCommit) {
                    // A parent we trust asks to pair again: it removed us on its side. Hand the
                    // connection to the pairing exchange; the user decides again.
                    executor.execute {
                        if (peer === p && p.pc == null) {
                            peer = null
                            _state.value = _state.value.copy(parentName = null)
                            onPairingConnection(p.connection, message)
                        } else {
                            p.connection.close()
                        }
                    }
                    return
                }
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
            else -> fail(p, "unexpected ${message.javaClass.simpleName}")
        }
    }

    private fun onHello(p: Peer, hello: Hello) {
        // The certificate already proved who this is; the hello must agree with it.
        if (p.pc != null || hello.version != PROTOCOL_VERSION || hello.deviceId != p.connection.peerDeviceId) {
            return fail(p, "bad hello")
        }
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
        const val PAIRING_BLOCK_MS = 60_000L
        const val MAX_CONNECTIONS = 8
    }
}
