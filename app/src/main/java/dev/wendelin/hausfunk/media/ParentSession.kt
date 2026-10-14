package dev.wendelin.hausfunk.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import dev.wendelin.hausfunk.data.SoundPrefs
import dev.wendelin.hausfunk.signaling.ControlMessage
import dev.wendelin.hausfunk.signaling.Decoded
import dev.wendelin.hausfunk.signaling.Level
import dev.wendelin.hausfunk.signaling.MessageCodec
import dev.wendelin.hausfunk.signaling.Ping
import dev.wendelin.hausfunk.signaling.Pong
import dev.wendelin.hausfunk.signaling.Status
import java.nio.ByteBuffer
import java.util.concurrent.Future
import dev.wendelin.hausfunk.SerialExecutor
import dev.wendelin.hausfunk.discovery.FoundBaby
import dev.wendelin.hausfunk.signaling.Answer
import dev.wendelin.hausfunk.signaling.Busy
import dev.wendelin.hausfunk.signaling.Bye
import dev.wendelin.hausfunk.signaling.Hello
import dev.wendelin.hausfunk.signaling.Ice
import dev.wendelin.hausfunk.signaling.Offer
import dev.wendelin.hausfunk.signaling.PROTOCOL_VERSION
import dev.wendelin.hausfunk.signaling.SignalingConnection
import dev.wendelin.hausfunk.signaling.SignalingMessage
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.signaling.TlsTransport
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** What actually arrives, for the debug overlay: resolution, frame rate, data rate, codec. */
data class VideoStats(val width: Int, val height: Int, val fps: Double, val kbps: Int, val codec: String)

/** The baby device's battery, from its last status message. */
data class BabyStatus(val battery: Int, val charging: Boolean)

/** The connection-loss alarm. */
enum class AlarmState {
    OFF,
    RINGING,

    /** The parent silenced it; the link is still down. */
    ACKNOWLEDGED,
}

sealed interface ParentState {
    /** The baby this state is about; null while idle. */
    val babyId: String? get() = (this as? WithBaby)?.baby?.deviceId

    sealed interface WithBaby : ParentState {
        val baby: FoundBaby
    }

    data object Idle : ParentState

    /** Trying to reach a baby that has not been connected yet in this watch. Retries on its own. */
    data class Connecting(override val baby: FoundBaby) : WithBaby
    data class Connected(override val baby: FoundBaby) : WithBaby

    /** The baby device already serves another parent; retried every few seconds. */
    data class Busy(override val baby: FoundBaby) : WithBaby

    /** Was connected and lost the link at [since] (elapsed ms); reconnecting on its own. */
    data class Reconnecting(override val baby: FoundBaby, val since: Long) : WithBaby
}

/**
 * Parent side: receives one baby device's audio and video. Audio plays as media; video goes to
 * the sinks attached with [attach]. Once connected, a lost link is retried on its own (last
 * address first, then what discovery resolves, with backoff), and the alarm rings if it stays
 * lost for the grace period. All WebRTC objects are touched only on the session's own thread.
 * [release] is final.
 */
class ParentSession(
    context: Context,
    identity: Identity,
    trustedIds: () -> Set<String>,
    /** The current address of a baby from discovery, if it has one. Called from any thread. */
    private val resolve: (deviceId: String) -> FoundBaby?,
) {
    private val transport = TlsTransport(identity, trustedIds)
    private val context = context.applicationContext
    private val executor = SerialExecutor("parent-session")
    private val _state = MutableStateFlow<ParentState>(ParentState.Idle)
    val state: StateFlow<ParentState> = _state.asStateFlow()
    private val _videoStats = MutableStateFlow<VideoStats?>(null)

    /** Measured every 2 s while connected; null otherwise. */
    val videoStats: StateFlow<VideoStats?> = _videoStats.asStateFlow()
    private var lastBytes = 0L
    private var lastStatsUs = 0.0

    private val _alarm = MutableStateFlow(AlarmState.OFF)
    val alarm: StateFlow<AlarmState> = _alarm.asStateFlow()
    private val _status = MutableStateFlow<BabyStatus?>(null)

    /** The baby's battery; null until its first status message on this connection. */
    val status: StateFlow<BabyStatus?> = _status.asStateFlow()
    private val _level = MutableStateFlow<Float?>(null)

    /** Live sound level in dBFS, 5 times per second; null while not connected. */
    val level: StateFlow<Float?> = _level.asStateFlow()
    private val _lastSound = MutableStateFlow<Long?>(null)

    /** Elapsed time of the last sound above the threshold; null if none since watching began. */
    val lastSound: StateFlow<Long?> = _lastSound.asStateFlow()

    /** Shared with the renderers so they can draw frames decoded here. */
    val eglBase: EglBase = EglBase.create()
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    // A baby monitor never pauses itself, so focus changes are ignored.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(mediaAttributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private var audioModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var link: Link? = null
    private val sinks = mutableSetOf<VideoSink>()
    private var released = false

    /** The baby being watched, with the address that last worked; null while idle. */
    private var target: FoundBaby? = null
    private lateinit var ownHello: Hello
    private var everConnected = false
    private var lostSince: Long? = null
    private val backoff = Backoff()
    private var retry: Future<*>? = null
    private var alarmTimer: Future<*>? = null
    private var graceMs = DEFAULT_GRACE_MS
    private val detector = SoundDetector()

    /**
     * Starts watching [baby], replacing whatever was watched. [resume] treats it as a connection
     * that already existed (the service was restarted), so the alarm covers it from the start.
     */
    fun connect(baby: FoundBaby, ownHello: Hello, resume: Boolean = false) = executor.execute {
        if (released) return@execute
        link?.let { close(it, sendBye = true) }
        cancelTimers()
        this.ownHello = ownHello
        target = baby
        everConnected = resume
        lostSince = null
        backoff.reset()
        detector.reset()
        _lastSound.value = null
        _status.value = null
        _alarm.value = AlarmState.OFF
        audioManager.requestAudioFocus(focusRequest)
        if (resume) markLost(baby) else _state.value = ParentState.Connecting(baby)
        attempt()
    }

    fun disconnect() = executor.execute { stopWatching() }

    fun release() = executor.execute {
        if (released) return@execute
        stopWatching()
        released = true
        factory?.dispose()
        audioModule?.release()
        eglBase.release()
        executor.shutdown()
    }

    /** Tries again right away, e.g. after the Wi-Fi came back or on "Retry". */
    fun retryNow() = executor.execute {
        if (target == null || link != null) return@execute
        backoff.reset()
        attempt()
    }

    /** Silences the alarm until the link is back and lost again. */
    fun acknowledgeAlarm() = executor.execute {
        if (_alarm.value == AlarmState.RINGING) _alarm.value = AlarmState.ACKNOWLEDGED
    }

    fun setGracePeriod(millis: Long) = executor.execute { graceMs = millis }

    fun setSoundPrefs(prefs: SoundPrefs) = executor.execute {
        detector.thresholdDbfs = prefs.thresholdDbfs
        detector.gateEnabled = prefs.gate
        detector.holdMs = prefs.holdSeconds * 1_000L
        link?.let(::applyGate)
    }

    /** Call whenever the trust store changes: a removed baby is disconnected at once. */
    fun onTrustChanged(ids: Set<String>) = executor.execute {
        target?.let { if (it.deviceId !in ids) stopWatching() }
    }

    /** Shows the video in [sink] while connected. Safe to call from any thread. */
    fun attach(sink: VideoSink) = executor.execute {
        if (sinks.add(sink)) link?.videoTrack?.addSink(sink)
    }

    fun detach(sink: VideoSink) = executor.execute {
        if (sinks.remove(sink)) link?.videoTrack?.removeSink(sink)
    }

    private fun stopWatching() {
        link?.let { close(it, sendBye = true) }
        cancelTimers()
        if (target != null) audioManager.abandonAudioFocusRequest(focusRequest)
        target = null
        _alarm.value = AlarmState.OFF
        _state.value = ParentState.Idle
    }

    private fun cancelTimers() {
        retry?.cancel(false)
        retry = null
        alarmTimer?.cancel(false)
        alarmTimer = null
    }

    private fun factory(): PeerConnectionFactory = factory ?: run {
        WebRtc.initialize(context)
        val module = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(mediaAttributes)
            .createAudioDeviceModule()
        audioModule = module
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    /** One connection attempt; [onDown] schedules the next if it fails. */
    private fun attempt() {
        val t = target ?: return
        retry?.cancel(false)
        retry = null
        val l = Link(t)
        link = l
        // The link counts as up only once pongs arrive; a setup that hangs anywhere is a failure.
        executor.schedule(SETUP_TIMEOUT_MS) { if (link === l && !l.alive) fail(l, "setup timeout") }
        thread(name = "parent-read") { run(l) }
    }

    /** Runs on the link's own thread: connects, then reads until the connection ends. */
    private fun run(l: Link) {
        try {
            val connection = open(l)
            l.connection = connection
            executor.execute { if (link !== l) connection.close() }
            connection.send(ownHello)
            while (true) {
                val message = connection.receive() ?: break
                if (message is Offer) connection.setReadTimeout(0) // set up; liveness comes from pongs
                executor.execute { if (link === l) handle(l, message) }
            }
        } catch (e: IOException) {
            Log.i(TAG, "baby connection ended: ${e.javaClass.simpleName}")
        }
        executor.execute { if (link === l) close(l, sendBye = false) }
    }

    /** The address that last worked first (fast), then the one discovery has now, if different. */
    private fun open(l: Link): SignalingConnection {
        val last = l.baby
        val found = resolve(last.deviceId)?.takeIf { it.address != last.address || it.port != last.port }
        if (found != null) {
            try {
                return transport.connect(last.address, last.port, last.deviceId, FAST_CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                Log.i(TAG, "last address failed, trying the resolved one")
            }
            l.baby = found.copy(name = last.name)
        }
        return transport.connect(l.baby.address, l.baby.port, l.baby.deviceId)
    }

    private fun handle(l: Link, message: SignalingMessage) {
        when (message) {
            is Hello -> if (message.version != PROTOCOL_VERSION || message.deviceId != l.baby.deviceId) {
                fail(l, "bad hello")
            }
            is Offer -> onOffer(l, message)
            is Ice -> l.pc?.let { pc -> l.ice.add(pc, IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)) }
            Busy -> close(l, sendBye = false, busy = true)
            Bye -> close(l, sendBye = false)
            else -> fail(l, "unexpected ${message.javaClass.simpleName}")
        }
    }

    private fun onOffer(l: Link, offer: Offer) {
        if (l.pc != null) return fail(l, "second offer")
        val pc = factory().createPeerConnection(WebRtc.rtcConfiguration(), observer(l)) ?: return fail(l, "no peer connection")
        l.pc = pc
        pc.setRemoteDescription(
            WebRtc.sdpObserver(executor, { fail(l, it) }) {
                if (link !== l) return@sdpObserver
                l.ice.flush(pc)
                pc.createAnswer(
                    WebRtc.sdpObserver(executor, { fail(l, it) }) { answer ->
                        if (link !== l || answer == null) return@sdpObserver
                        pc.setLocalDescription(
                            WebRtc.sdpObserver(executor, { fail(l, it) }) {
                                if (link === l) send(l, Answer(answer.description))
                            },
                            answer,
                        )
                    },
                    MediaConstraints(),
                )
            },
            SessionDescription(SessionDescription.Type.OFFER, offer.sdp),
        )
    }

    private fun observer(l: Link) = object : PeerObserver() {
        override fun onIceCandidate(candidate: IceCandidate) = executor.execute {
            if (link === l) send(l, Ice(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onTrack(transceiver: RtpTransceiver) = executor.execute {
            if (link !== l) return@execute
            when (val track = transceiver.receiver.track()) {
                is VideoTrack -> {
                    l.videoTrack = track
                    sinks.forEach(track::addSink)
                }
                is AudioTrack -> {
                    l.audioTrack = track
                    applyGate(l, force = true)
                }
                else -> Unit
            }
        }

        override fun onDataChannel(channel: DataChannel) = executor.execute {
            if (link !== l || l.control != null) return@execute
            l.control = channel
            channel.registerObserver(controlObserver(l, channel))
            if (channel.state() == DataChannel.State.OPEN) startPinging(l)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = executor.execute {
            if (link !== l) return@execute
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> onConnected(l)
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.DISCONNECTED,
                -> close(l, sendBye = true)
                else -> Unit
            }
        }
    }

    private fun onConnected(l: Link) {
        if (_state.value is ParentState.Connected) return
        target = l.baby // the address that worked is tried first next time
        everConnected = true
        lostSince = null
        alarmTimer?.cancel(false)
        alarmTimer = null
        _alarm.value = AlarmState.OFF
        backoff.reset()
        _state.value = ParentState.Connected(l.baby)
        pollStats(l)
    }

    private fun controlObserver(l: Link, channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            if (channel.state() == DataChannel.State.OPEN) executor.execute { if (link === l) startPinging(l) }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            // The buffer is only valid during this call.
            val text = Charsets.UTF_8.decode(buffer.data).toString()
            executor.execute { if (link === l) onControl(l, text) }
        }
    }

    private fun onControl(l: Link, text: String) {
        val now = SystemClock.elapsedRealtime()
        when (val decoded = MessageCodec.decodeControl(text)) {
            is Decoded.Message -> when (val message = decoded.message) {
                Pong -> {
                    l.lastPong = now
                    l.alive = true
                }
                is Status -> _status.value = BabyStatus(message.battery.coerceIn(-1, 100), message.charging)
                is Level -> {
                    val db = message.dbfs.takeIf { it.isFinite() }?.coerceIn(LevelMeter.FLOOR_DBFS, 0f) ?: return
                    _level.value = db
                    detector.onLevel(db, now)
                    _lastSound.value = detector.lastSoundAt
                    applyGate(l)
                }
                else -> Unit
            }
            is Decoded.Unknown -> Unit
            Decoded.Malformed -> if (!l.loggedMalformed) {
                l.loggedMalformed = true
                Log.w(TAG, "malformed control message")
            }
        }
    }

    /** Pings every 2 s; a baby that has not answered for 6 s is gone, whatever the sockets say. */
    private fun startPinging(l: Link) {
        if (l.pinging) return
        l.pinging = true
        l.lastPong = SystemClock.elapsedRealtime()
        ping(l)
    }

    private fun ping(l: Link) {
        if (link !== l) return
        if (SystemClock.elapsedRealtime() - l.lastPong > LIVENESS_TIMEOUT_MS) return fail(l, "no pong")
        sendControl(l, Ping)
        executor.schedule(PING_INTERVAL_MS) { ping(l) }
    }

    private fun sendControl(l: Link, message: ControlMessage) {
        val channel = l.control ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val bytes = MessageCodec.encode(message).toByteArray(Charsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    /** Mutes playback below the threshold when the gate is on; re-checked with every level. */
    private fun applyGate(l: Link, force: Boolean = false) {
        val open = detector.gateOpen(SystemClock.elapsedRealtime())
        if (open == l.gateOpen && !force) return
        l.gateOpen = open
        l.audioTrack?.setVolume(if (open) 1.0 else 0.0)
    }

    private fun pollStats(l: Link) {
        val pc = l.pc ?: return
        if (link !== l) return
        pc.getStats { report -> executor.execute { if (link === l) onStats(report) } }
        executor.schedule(STATS_INTERVAL_MS) { pollStats(l) }
    }

    private fun onStats(report: RTCStatsReport) {
        val stats = report.statsMap.values
        val inbound = stats.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" } ?: return
        val m = inbound.members
        val bytes = (m["bytesReceived"] as? Number)?.toLong() ?: 0L
        val kbps = if (lastStatsUs > 0 && inbound.timestampUs > lastStatsUs) {
            ((bytes - lastBytes) * 8 * 1000 / (inbound.timestampUs - lastStatsUs)).toInt()
        } else {
            0
        }
        lastBytes = bytes
        lastStatsUs = inbound.timestampUs
        val codec = stats.firstOrNull { it.id == m["codecId"] }?.members?.get("mimeType") as? String
        _videoStats.value = VideoStats(
            width = (m["frameWidth"] as? Number)?.toInt() ?: 0,
            height = (m["frameHeight"] as? Number)?.toInt() ?: 0,
            fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: 0.0,
            kbps = kbps,
            codec = codec?.substringAfter('/') ?: "?",
        )
    }

    private fun send(l: Link, message: SignalingMessage) {
        try {
            l.connection?.send(message)
        } catch (e: IOException) {
            close(l, sendBye = false)
        }
    }

    private fun fail(l: Link, reason: String) {
        Log.w(TAG, "closing baby connection: $reason")
        if (link === l) close(l, sendBye = true)
    }

    /** Ends [l]; while still watching, the next attempt is scheduled. */
    private fun close(l: Link, sendBye: Boolean, busy: Boolean = false) {
        if (link !== l) return
        link = null
        val connection = l.connection
        if (sendBye && connection != null) {
            try {
                connection.send(Bye)
            } catch (e: IOException) {
                // Already gone.
            }
        }
        connection?.close()
        l.videoTrack?.let { track -> sinks.forEach(track::removeSink) }
        l.videoTrack = null
        l.audioTrack = null
        l.control?.unregisterObserver()
        l.control?.dispose()
        l.control = null
        l.pc?.dispose()
        l.pc = null
        _videoStats.value = null
        _level.value = null
        lastBytes = 0L
        lastStatsUs = 0.0
        onDown(busy)
    }

    private fun onDown(busy: Boolean) {
        val t = target ?: return
        when {
            everConnected -> markLost(t)
            busy -> _state.value = ParentState.Busy(t)
            else -> _state.value = ParentState.Connecting(t)
        }
        retry = executor.schedule(if (busy) BUSY_RETRY_MS else backoff.next()) {
            retry = null
            if (link == null) attempt()
        }
    }

    /** The link is down after having been up: reconnect, and ring after the grace period. */
    private fun markLost(t: FoundBaby) {
        val since = lostSince ?: SystemClock.elapsedRealtime().also { lostSince = it }
        _status.value = null
        _state.value = ParentState.Reconnecting(t, since)
        if (alarmTimer == null && _alarm.value == AlarmState.OFF) {
            alarmTimer = executor.schedule(graceMs) {
                alarmTimer = null
                if (lostSince != null && _alarm.value == AlarmState.OFF) _alarm.value = AlarmState.RINGING
            }
        }
    }

    private class Link(@Volatile var baby: FoundBaby) {
        @Volatile var connection: SignalingConnection? = null
        var pc: PeerConnection? = null
        var videoTrack: VideoTrack? = null
        var audioTrack: AudioTrack? = null
        var control: DataChannel? = null
        val ice = PendingIce()
        var pinging = false
        var lastPong = 0L

        /** Pongs arrive: the baby answers end to end. */
        var alive = false
        var gateOpen = true
        var loggedMalformed = false
    }

    private companion object {
        const val TAG = "ParentSession"
        const val STATS_INTERVAL_MS = 2_000L
        const val PING_INTERVAL_MS = 2_000L
        const val LIVENESS_TIMEOUT_MS = 6_000L
        const val SETUP_TIMEOUT_MS = 20_000L
        const val FAST_CONNECT_TIMEOUT_MS = 3_000
        const val BUSY_RETRY_MS = 10_000L
        const val DEFAULT_GRACE_MS = 15_000L
    }
}
