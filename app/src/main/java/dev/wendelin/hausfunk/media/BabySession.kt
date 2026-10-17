package dev.wendelin.hausfunk.media

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.wendelin.hausfunk.SerialExecutor
import dev.wendelin.hausfunk.data.DeviceName
import dev.wendelin.hausfunk.discovery.BabyAdvertiser
import dev.wendelin.hausfunk.identity.Fingerprint
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.identity.PeerClass
import dev.wendelin.hausfunk.pairing.PairedPeer
import dev.wendelin.hausfunk.pairing.PairingRunner
import dev.wendelin.hausfunk.pairing.PairingState
import dev.wendelin.hausfunk.signaling.Answer
import dev.wendelin.hausfunk.signaling.Busy
import dev.wendelin.hausfunk.signaling.Bye
import dev.wendelin.hausfunk.signaling.ControlMessage
import dev.wendelin.hausfunk.signaling.Decoded
import dev.wendelin.hausfunk.signaling.Hello
import dev.wendelin.hausfunk.signaling.Ice
import dev.wendelin.hausfunk.signaling.Level
import dev.wendelin.hausfunk.signaling.MessageCodec
import dev.wendelin.hausfunk.signaling.Offer
import dev.wendelin.hausfunk.signaling.PROTOCOL_VERSION
import dev.wendelin.hausfunk.signaling.PairCommit
import dev.wendelin.hausfunk.signaling.Ping
import dev.wendelin.hausfunk.signaling.Pong
import dev.wendelin.hausfunk.signaling.SignalingConnection
import dev.wendelin.hausfunk.signaling.SignalingMessage
import dev.wendelin.hausfunk.signaling.SignalingServer
import dev.wendelin.hausfunk.signaling.Status
import dev.wendelin.hausfunk.signaling.TlsTransport
import dev.wendelin.hausfunk.signaling.Unpair
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink

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
    /** A parent asking to pair, from when the emojis show until the user dismisses the result. */
    val pairing: PairingRunner? = null,
)

/**
 * Baby side: streams camera and microphone ([BabyMedia]) to one paired parent (several parents
 * arrive in phase 6), with battery and sound level on the data channel. Any device may ask to
 * pair; nothing is stored unless the user confirms the emojis on both phones. Every connection
 * says first what it wants (`hello` or `pair_commit`), read on its handshake thread. All WebRTC
 * objects are touched only on the session's own thread. [stop] is final.
 */
class BabySession(
    context: Context,
    private val identity: Identity,
    private val trustedIds: () -> Set<String>,
    /** Stores a newly paired parent. */
    private val onPaired: (PairedPeer) -> Unit,
    /** A parent said it removed the pairing on its side. */
    private val onUnpaired: (deviceId: String, name: String) -> Unit,
) {
    private val transport = TlsTransport(identity, trustedIds)
    private val context = context.applicationContext
    private val executor = SerialExecutor("baby-session")
    private val _state = MutableStateFlow(BabyState())
    val state: StateFlow<BabyState> = _state.asStateFlow()

    /** Shared with the local preview renderer. */
    val eglBase: EglBase = EglBase.create()
    private val media = BabyMedia(this.context, eglBase) { working ->
        _state.update { it.copy(cameraError = !working) }
    }
    private var mediaOpen = false

    private val advertiser = BabyAdvertiser(this.context)
    private var server: SignalingServer? = null
    private var peer: Peer? = null
    private var stopped = false
    private lateinit var ownHello: Hello
    private var handshaking = 0

    private var pairing: PairingRunner? = null
    private var pairingActive = false

    /** Devices whose last pairing attempt failed, until when they are ignored (elapsed ms). */
    private val pairingBlockedUntil = mutableMapOf<String, Long>()

    /** After any failed attempt, no pairing for a while: new keys per attempt get no more tries. */
    private var pairingPausedUntil = 0L

    private val battery = BatteryWatcher(this.context) { executor.execute { peer?.let(::sendStatus) } }

    fun start(name: String, appVersion: String) = executor.execute {
        if (stopped) return@execute
        ownHello = Hello(PROTOCOL_VERSION, identity.deviceId, name, appVersion)
        try {
            WebRtc.initialize(context)
            mediaOpen = true
            media.start()
            _state.update {
                it.copy(cameraAvailable = media.videoTrack != null, canSwitchCamera = media.canSwitchCamera)
            }
            val s = transport.listen()
            server = s
            advertiser.start(ownHello.name, identity.deviceId, s.port)
            thread(name = "baby-accept") { acceptLoop(s) }
            battery.start()
            executor.schedule(LEVEL_INTERVAL_MS, ::sendLevel)
            executor.schedule(STATUS_INTERVAL_MS, ::sendStatusPeriodically)
        } catch (e: IOException) {
            failStart(e)
        } catch (e: RuntimeException) {
            // Camera or audio hardware refused; an exception here would kill the app.
            failStart(e)
        }
    }

    fun stop() = executor.execute {
        if (stopped) return@execute
        stopped = true
        pairing?.cancel()
        peer?.let { close(it, sendBye = true) }
        closeAll()
        eglBase.release()
        executor.shutdown()
    }

    /** Hides the result of the last pairing. */
    fun dismissPairing() = executor.execute {
        if (!pairingActive) _state.update { it.copy(pairing = null) }
    }

    /** Call whenever the trust store changes: a removed parent is told so and disconnected at once. */
    fun onTrustChanged(ids: Set<String>) = executor.execute {
        val p = peer ?: return@execute
        if (p.connection.peerDeviceId in ids) return@execute
        try {
            p.connection.send(Unpair)
        } catch (e: IOException) {
            // It learns on its next connection instead.
        }
        close(p, sendBye = false)
    }

    /** Rejects or ends a pairing in progress ("Passt nicht", back). */
    fun cancelPairing() = executor.execute { pairing?.cancel() }

    /** Front and back camera. */
    fun switchCamera() = executor.execute { media.switchCamera() }

    /** Shows the camera picture in [sink] (the baby's own preview). Safe to call from any thread. */
    fun attachPreview(sink: VideoSink) = executor.execute { media.attachPreview(sink) }

    fun detachPreview(sink: VideoSink) = executor.execute { media.detachPreview(sink) }

    private fun failStart(e: Exception) {
        Log.w(TAG, "could not start", e)
        // Camera and microphone are released at once, not only on "Stop".
        closeAll()
        _state.update { it.copy(failed = true) }
    }

    /** Releases capture, server and advertising; safe to call twice. */
    private fun closeAll() {
        battery.stop()
        advertiser.stop()
        server?.close()
        server = null
        if (mediaOpen) media.dispose()
        mediaOpen = false
    }

    private fun acceptLoop(s: SignalingServer) {
        while (true) {
            val pending = try {
                s.accept()
            } catch (e: IOException) {
                return // server closed
            }
            if (!executor.tryExecute { onAccepted(pending) }) pending.close()
        }
    }

    private fun onAccepted(pending: SignalingServer.Pending) {
        val open = handshaking + (if (peer != null) 1 else 0) + (if (pairingActive) 1 else 0)
        if (stopped || open >= MAX_CONNECTIONS) {
            pending.close()
            return
        }
        handshaking++
        thread(name = "baby-handshake") { greet(pending) }
    }

    /**
     * On the handshake thread: TLS, then the first message, within one deadline so nobody can hold
     * a connection slot by sending slowly. A parent that still trusts us after we removed it
     * sends `hello` and gets `unpair`, so it can tell its user.
     */
    private fun greet(pending: SignalingServer.Pending) {
        val done = AtomicBoolean(false)
        executor.schedule(GREETING_TIMEOUT_MS) { if (!done.get()) pending.close() }
        var connection: SignalingConnection? = null
        var first: SignalingMessage? = null
        try {
            connection = pending.handshake()
            first = connection.receive()
        } catch (e: IOException) {
            Log.i(TAG, "connection refused: ${e.javaClass.simpleName}")
        }
        done.set(true)
        val c = connection
        val trusted = c != null && c.peerClass == PeerClass.TRUSTED && c.peerDeviceId in trustedIds()
        if (c != null && first is Hello && !trusted) {
            try {
                c.send(Unpair)
            } catch (e: IOException) {
                // Gone already.
            }
        }
        val accepted = executor.tryExecute {
            handshaking--
            when {
                c == null -> Unit
                first is PairCommit -> onPairingConnection(c, first)
                first is Hello && trusted -> onIncoming(c, first)
                else -> c.close()
            }
        }
        if (!accepted) c?.close()
    }

    private fun onPairingConnection(connection: SignalingConnection, commit: PairCommit) {
        // One pairing at a time; after a failed attempt nobody can pair for a while, and that
        // device for longer, so nobody on the Wi-Fi can keep popping up the pairing screen.
        val now = SystemClock.elapsedRealtime()
        pairingBlockedUntil.entries.removeAll { it.value <= now }
        if (stopped || pairingActive || now < pairingPausedUntil || connection.peerDeviceId in pairingBlockedUntil) {
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
            onFinished = { executor.execute { onPairingFinished(runner, connection.peerDeviceId) } },
        )
        pairing = runner
        // The screen shows it once there are emojis; an attempt that ends earlier never shows.
        _state.update { it.copy(pairing = runner) }
        runner.start(commit)
    }

    private fun onPairingFinished(runner: PairingRunner, deviceId: String) {
        pairingActive = false
        if (runner.state.value is PairingState.Paired) {
            // Done: back to the baby screen without an extra "OK"; the parent connects right away.
            _state.update { if (it.pairing === runner) it.copy(pairing = null) else it }
            return
        }
        if (runner.state.value != PairingState.Failed) return
        val now = SystemClock.elapsedRealtime()
        pairingBlockedUntil[deviceId] = now + PAIRING_BLOCK_MS
        pairingPausedUntil = now + PAIRING_PAUSE_MS
        if (!runner.reachedCompare) _state.update { if (it.pairing === runner) it.copy(pairing = null) else it }
    }

    private fun onIncoming(connection: SignalingConnection, hello: Hello) {
        // Trust may have changed since the handshake (a parent removed meanwhile).
        if (stopped || connection.peerDeviceId !in trustedIds()) return connection.close()
        // The same parent connecting again (it reconnected or tapped us again) replaces its old
        // connection instead of being told that we are busy.
        peer?.let { if (it.connection.peerDeviceId == connection.peerDeviceId) close(it, sendBye = false) }
        if (peer != null) {
            // One parent at a time until phase 6.
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
        onHello(p, hello)
        if (peer === p) thread(name = "baby-read") { readLoop(p) }
    }

    private fun readLoop(p: Peer) {
        try {
            while (true) {
                val message = p.connection.receive() ?: break
                if (message == Unpair) {
                    executor.execute {
                        if (peer === p) close(p, sendBye = false)
                        onUnpaired(p.connection.peerDeviceId, p.name)
                    }
                    return
                }
                if (message is Answer) p.connection.setReadTimeout(0) // set up; liveness comes from pings
                executor.execute { if (peer === p) handle(p, message) }
            }
        } catch (e: IOException) {
            Log.i(TAG, "parent connection ended: ${e.javaClass.simpleName}")
        }
        executor.execute { if (peer === p) close(p, sendBye = false) }
    }

    private fun handle(p: Peer, message: SignalingMessage) {
        when (message) {
            is Answer -> p.pc?.let { pc ->
                pc.setRemoteDescription(
                    WebRtc.sdpObserver(executor, { fail(p, it) }) {
                        if (peer === p) {
                            p.ice.flush(pc)
                            // The parent pings once the data channel is open; allow time for ICE.
                            p.lastPing = SystemClock.elapsedRealtime() + SETUP_GRACE_MS - LIVENESS_TIMEOUT_MS
                            checkLiveness(p)
                            limitVideo(p)
                            // A home Wi-Fi can do far more than WebRTC's cautious default start;
                            // its bandwidth estimate still lowers this when the link cannot.
                            pc.setBitrate(MIN_BITRATE_BPS, START_BITRATE_BPS, MAX_BITRATE_BPS)
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
        if (hello.version != PROTOCOL_VERSION || hello.deviceId != p.connection.peerDeviceId) {
            return fail(p, "bad hello")
        }
        p.name = DeviceName.sanitize(hello.name)
        if (!send(p, ownHello)) return
        val factory = media.factory
        val pc = factory.createPeerConnection(WebRtc.rtcConfiguration(), observer(p)) ?: return fail(p, "no peer connection")
        p.pc = pc
        val sendOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(STREAM_ID))
        pc.addTransceiver(media.audioTrack, sendOnly)
        media.videoTrack?.let { track ->
            val video = pc.addTransceiver(track, sendOnly)
            video.setCodecPreferences(media.videoCodecs)
            p.video = video
        }
        p.control = pc.createDataChannel("control", DataChannel.Init().apply { ordered = true })
            .also { it.registerObserver(controlObserver(p, it)) }
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
        _state.update { it.copy(parentName = p.name) }
    }

    private fun controlObserver(p: Peer, channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            if (channel.state() == DataChannel.State.OPEN) executor.execute { if (peer === p) sendStatus(p) }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            // The buffer is only valid during this call.
            val text = Charsets.UTF_8.decode(buffer.data).toString()
            executor.execute { if (peer === p) onControl(p, text) }
        }
    }

    private fun onControl(p: Peer, text: String) {
        when (val decoded = MessageCodec.decodeControl(text)) {
            is Decoded.Message -> if (decoded.message == Ping) {
                p.lastPing = SystemClock.elapsedRealtime()
                sendControl(p, Pong)
                // The parent surely listens now; the status sent on open may have come too early.
                if (!p.pinged) sendStatus(p)
                p.pinged = true
            }
            // `watching` (video gating) arrives in phase 6; other types are not for us.
            is Decoded.Unknown -> Unit
            Decoded.Malformed -> if (!p.loggedMalformed) {
                p.loggedMalformed = true
                Log.w(TAG, "malformed control message")
            }
        }
    }

    /** Drops a parent that stopped pinging: it is gone, even if the sockets have not noticed. */
    private fun checkLiveness(p: Peer) {
        if (peer !== p) return
        if (SystemClock.elapsedRealtime() - p.lastPing > LIVENESS_TIMEOUT_MS) {
            Log.i(TAG, "parent stopped pinging")
            return close(p, sendBye = true)
        }
        executor.schedule(PING_CHECK_MS) { checkLiveness(p) }
    }

    private fun sendControl(p: Peer, message: ControlMessage) {
        val channel = p.control ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val bytes = MessageCodec.encode(message).toByteArray(Charsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun sendStatus(p: Peer) {
        val b = battery.current() ?: Battery(-1, false)
        sendControl(p, Status(battery = b.percent, charging = b.charging, parents = 1, thermalReduced = false))
    }

    private fun sendStatusPeriodically() {
        if (stopped) return
        peer?.let(::sendStatus)
        executor.schedule(STATUS_INTERVAL_MS, ::sendStatusPeriodically)
    }

    private fun sendLevel() {
        if (stopped) return
        val db = media.takeLevel()
        if (db != null) peer?.let { sendControl(it, Level(Math.round(db * 10) / 10f)) }
        executor.schedule(LEVEL_INTERVAL_MS, ::sendLevel)
    }

    private fun limitVideo(p: Peer) {
        val sender = p.video?.sender ?: return
        val parameters = sender.parameters
        parameters.encodings.forEach {
            it.maxBitrateBps = MAX_BITRATE_BPS
            it.maxFramerate = BabyMedia.FPS
        }
        // When bandwidth or CPU runs short, WebRTC lowers resolution and frame rate together; the
        // parent's view ignores the small aspect changes that come with it.
        parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
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
        p.control?.unregisterObserver()
        p.control?.dispose()
        p.control = null
        p.pc?.dispose()
        p.pc = null
        _state.update { it.copy(parentName = null) }
    }

    private class Peer(val connection: SignalingConnection) {
        var name: String = ""
        var pc: PeerConnection? = null
        var video: RtpTransceiver? = null
        var control: DataChannel? = null
        val ice = PendingIce()

        /** Elapsed time of the last ping; set ahead while the connection is set up. */
        var lastPing = 0L
        var pinged = false
        var loggedMalformed = false
    }

    private companion object {
        const val TAG = "BabySession"
        const val STREAM_ID = "hausfunk"
        const val MIN_BITRATE_BPS = 300_000
        const val START_BITRATE_BPS = 2_500_000
        const val MAX_BITRATE_BPS = 4_000_000
        const val PAIRING_BLOCK_MS = 60_000L
        const val PAIRING_PAUSE_MS = 10_000L
        const val MAX_CONNECTIONS = 8
        const val GREETING_TIMEOUT_MS = 15_000L
        const val LEVEL_INTERVAL_MS = 200L
        const val STATUS_INTERVAL_MS = 30_000L
        const val PING_CHECK_MS = 2_000L
        const val LIVENESS_TIMEOUT_MS = 6_000L
        const val SETUP_GRACE_MS = 15_000L
    }
}
