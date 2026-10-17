package dev.wendelin.hausfunk.media

import android.content.Context
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
import dev.wendelin.hausfunk.signaling.Unpair
import dev.wendelin.hausfunk.signaling.Watching
import java.net.InetAddress
import java.net.Socket
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
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/** The baby device's battery, from its last status message. */
data class BabyStatus(val battery: Int, val charging: Boolean, val hot: Boolean = false)

sealed interface ParentState {
    /** The baby this state is about; null while idle. */
    val babyId: String? get() = (this as? WithBaby)?.baby?.deviceId

    sealed interface WithBaby : ParentState {
        val baby: FoundBaby
    }

    data object Idle : ParentState

    /** Trying to reach a baby that has not answered yet in this watch. Retries on its own. */
    data class Connecting(override val baby: FoundBaby) : WithBaby

    /** The baby answers end to end (pongs arrive). */
    data class Connected(override val baby: FoundBaby) : WithBaby

    /** The baby device already serves another parent; retried every few seconds. */
    data class Busy(override val baby: FoundBaby) : WithBaby

    /** Was connected and lost the link; reconnecting on its own, the alarm may ring. */
    data class Reconnecting(override val baby: FoundBaby) : WithBaby
}

/**
 * Parent side: receives one baby device's audio and video. Audio plays as media; video goes to
 * the sinks attached with [attach]. A link counts as up only once the baby answers pings; a lost
 * link is retried on its own (last address first, then what discovery resolves, with backoff),
 * and [LossAlarm] decides when the alarm rings. All WebRTC objects are touched only on the
 * session's own thread. [release] is final.
 */
class ParentSession(
    context: Context,
    identity: Identity,
    trustedIds: () -> Set<String>,
    /** The current address of a baby from discovery, if it has one. Called from any thread. */
    private val resolve: (deviceId: String) -> FoundBaby?,
    /** Pins a signaling socket to the local network that reaches the address. */
    private val bind: (Socket, InetAddress) -> Unit,
    /** The baby said it no longer trusts us (it removed the pairing). Watching has ended. */
    private val onUnpaired: (FoundBaby) -> Unit,
    /** Whether this phone is on any local network; tells the alarm whose side the problem is. */
    private val hasLan: () -> Boolean,
) {
    private val transport = TlsTransport(identity, trustedIds)
    private val context = context.applicationContext
    private val executor = SerialExecutor("parent-session")
    private val audio = ParentAudio(this.context)

    private val _state = MutableStateFlow<ParentState>(ParentState.Idle)
    val state: StateFlow<ParentState> = _state.asStateFlow()
    private val _alarm = MutableStateFlow(AlarmState.OFF)
    val alarm: StateFlow<AlarmState> = _alarm.asStateFlow()
    private val _videoStats = MutableStateFlow<VideoStats?>(null)

    /** Measured every 2 s while connected; null otherwise. */
    val videoStats: StateFlow<VideoStats?> = _videoStats.asStateFlow()
    private val _status = MutableStateFlow<BabyStatus?>(null)

    /** The baby's battery; null until its first status message on this connection. */
    val status: StateFlow<BabyStatus?> = _status.asStateFlow()
    private val _level = MutableStateFlow<Float?>(null)

    /** Live sound level in dBFS, 5 times per second; null while not connected. */
    val level: StateFlow<Float?> = _level.asStateFlow()
    private val _lastSound = MutableStateFlow<Long?>(null)

    /** Elapsed time of the last sound above the threshold; null if none since watching began. */
    val lastSound: StateFlow<Long?> = _lastSound.asStateFlow()
    private val _videoArriving = MutableStateFlow(false)

    /** Video frames arrive on the current connection; false again after every loss. */
    val videoArriving: StateFlow<Boolean> = _videoArriving.asStateFlow()
    private val history = SoundHistory()
    private val _history = MutableStateFlow<List<SoundHistory.Bucket>>(emptyList())

    /** The last 30 minutes of level, refreshed every 5 s; gaps where nothing arrived. */
    val soundHistory: StateFlow<List<SoundHistory.Bucket>> = _history.asStateFlow()
    private var historyBucket = -1L
    private val _noLan = MutableStateFlow(false)

    /** This phone itself has no Wi-Fi: the alarm says so instead of blaming the baby. */
    val noLan: StateFlow<Boolean> = _noLan.asStateFlow()
    private val _gateOpen = MutableStateFlow(true)

    /** False while the noise gate mutes the sound (only with "only above threshold" on). */
    val gateOpen: StateFlow<Boolean> = _gateOpen.asStateFlow()

    /** Shared with the renderers so they can draw frames decoded here. */
    val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null

    /** The networks changed since the factory was made; rebuilt before the next attempt. */
    private var networkChanged = false
    @Volatile private var link: Link? = null
    private val sinks = mutableSetOf<VideoSink>()
    private var released = false

    /** The baby being watched, with the address that last worked; null while idle. */
    private var target: FoundBaby? = null
    private lateinit var ownHello: Hello
    private val backoff = Backoff()
    private var retry: Future<*>? = null
    private val lossAlarm = LossAlarm()
    private var alarmCheck: Future<*>? = null

    /** The alarm already waited once for an attempt in progress during this loss. */
    private var lastChanceUsed = false
    private val detector = SoundDetector()

    /** The video is on screen (app open or picture-in-picture); otherwise the baby sends sound only. */
    @Volatile private var watching = false

    /**
     * Starts watching [baby], replacing whatever was watched. [resume] treats it as a connection
     * that already existed (the service was restarted), so the alarm covers it from the start.
     */
    fun connect(baby: FoundBaby, ownHello: Hello, graceMs: Long, resume: Boolean = false) = executor.execute {
        if (released) return@execute
        endWatch()
        this.ownHello = ownHello
        target = baby
        backoff.reset()
        detector.reset()
        history.clear()
        _history.value = emptyList()
        applyGate()
        lossAlarm.graceMs = graceMs
        lossAlarm.reset(resume)
        audio.requestFocus()
        _state.value = if (resume) ParentState.Reconnecting(baby) else ParentState.Connecting(baby)
        if (resume) linkDown()
        attempt()
    }

    fun release() = executor.execute {
        if (released) return@execute
        released = true
        endWatch()
        _state.value = ParentState.Idle
        factory?.dispose()
        audio.releaseModule()
        eglBase.release()
        executor.shutdown()
    }

    /** Tries again right away, e.g. on "Retry". */
    fun retryNow() = executor.execute {
        if (target == null || link != null) return@execute
        backoff.reset()
        attempt()
    }

    /**
     * A Wi-Fi or Ethernet network appeared or went. libwebrtc may hold on to the old network
     * list, so the next attempt starts with a fresh factory; if the link is down, it starts now.
     */
    fun onNetworkChanged() = executor.execute {
        networkChanged = true
        _noLan.value = !hasLan()
        if (target == null) return@execute
        backoff.reset()
        val l = link
        when {
            l == null -> attempt()
            // An attempt still being set up may hang on the old network: start over (in 1 s).
            !l.alive -> close(l, sendBye = false)
        }
    }

    /** Silences the alarm until the link is back and lost again. */
    fun acknowledgeAlarm() = executor.execute {
        lossAlarm.acknowledge()
        publishAlarm()
    }

    /** Call when the video becomes visible or hidden; the baby stops encoding video for us meanwhile. */
    fun setWatching(visible: Boolean) = executor.execute {
        if (visible == watching) return@execute
        watching = visible
        // The last frame may be old (the baby sent no video meanwhile): wait for a new one.
        if (visible) _videoArriving.value = false
        link?.let { sendControl(it, Watching(visible)) }
    }

    fun setGracePeriod(millis: Long) = executor.execute {
        lossAlarm.graceMs = millis
        scheduleAlarmCheck()
    }

    fun setSoundPrefs(prefs: SoundPrefs) = executor.execute {
        detector.thresholdDbfs = prefs.thresholdDbfs
        detector.gateEnabled = prefs.gate
        detector.holdMs = prefs.holdSeconds * 1_000L
        applyGate()
    }

    /** The user removed this baby: tell it, if connected, so it forgets us too. */
    fun sendUnpair() = executor.execute {
        try {
            link?.connection?.send(Unpair)
        } catch (e: IOException) {
            // It learns on its next connection instead.
        }
    }

    /** Shows the video in [sink] while connected. Safe to call from any thread. */
    fun attach(sink: VideoSink) = executor.execute {
        if (sinks.add(sink)) link?.videoTrack?.addSink(sink)
    }

    fun detach(sink: VideoSink) = executor.execute {
        if (sinks.remove(sink)) link?.videoTrack?.removeSink(sink)
    }

    /** Ends the current watch, if any, without reconnecting or alarming. */
    private fun endWatch() {
        // No target first, so closing the link does not count as a loss.
        target = null
        link?.let { close(it, sendBye = true) }
        retry?.cancel(false)
        retry = null
        alarmCheck?.cancel(false)
        alarmCheck = null
        lossAlarm.stop()
        lastChanceUsed = false
        publishAlarm()
        audio.abandonFocus()
        _gateOpen.value = true
        audio.setMuted(false)
        _lastSound.value = null
        _status.value = null
    }

    private fun factory(): PeerConnectionFactory = factory ?: run {
        WebRtc.initialize(context)
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio.createModule())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    /** One connection attempt; [linkDown] schedules the next if it fails. */
    private fun attempt() {
        val t = target ?: return
        retry?.cancel(false)
        retry = null
        if (networkChanged) {
            networkChanged = false
            factory?.dispose()
            factory = null
            audio.releaseModule()
        }
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
            // Watching ended or moved on while connecting (also after release, when the
            // executor no longer runs anything): never greet the baby from a stale link.
            if (link !== l) {
                connection.close()
                return
            }
            connection.send(ownHello.copy(watching = watching))
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
                return transport.connect(last.address, last.port, last.deviceId, FAST_CONNECT_TIMEOUT_MS) {
                    bind(it, last.address)
                }
            } catch (e: IOException) {
                Log.i(TAG, "last address failed, trying the resolved one")
            }
            l.baby = found.copy(name = last.name)
        }
        val b = l.baby
        return transport.connect(b.address, b.port, b.deviceId) { bind(it, b.address) }
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
            Unpair -> {
                // Authentic: it came over the connection pinned to this baby's certificate.
                val baby = l.baby
                endWatch()
                _state.value = ParentState.Idle
                onUnpaired(baby)
            }
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
            val track = transceiver.receiver.track() as? VideoTrack ?: return@execute
            if (link !== l) return@execute
            l.videoTrack = track
            track.addSink(l.frameWatch)
            sinks.forEach(track::addSink)
        }

        override fun onDataChannel(channel: DataChannel) = executor.execute {
            if (link !== l || l.control != null) return@execute
            l.control = channel
            channel.registerObserver(controlObserver(l, channel))
            if (channel.state() == DataChannel.State.OPEN) startPinging(l)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = executor.execute {
            if (link !== l) return@execute
            // Up only counts with pongs (see onControl); a broken ICE path is a loss right away.
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.DISCONNECTED
            ) {
                close(l, sendBye = true)
            }
        }
    }

    /** The first pong of [l]: the baby answers end to end. */
    private fun onUp(l: Link) {
        l.alive = true
        target = l.baby // the address that worked is tried first next time
        backoff.reset()
        lossAlarm.onUp()
        lastChanceUsed = false
        alarmCheck?.cancel(false)
        alarmCheck = null
        publishAlarm()
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
                    if (!l.alive) onUp(l)
                }
                is Status -> _status.value = BabyStatus(message.battery.coerceIn(-1, 100), message.charging, message.thermalReduced)
                is Level -> {
                    val db = message.dbfs.takeIf { it.isFinite() }?.coerceIn(LevelMeter.FLOOR_DBFS, 0f) ?: return
                    _level.value = db
                    detector.onLevel(db, now)
                    _lastSound.value = detector.lastSoundAt
                    history.add(db, detector.sounding, now)
                    // Republished once per bucket, which is all the strip can show.
                    val bucket = now / SoundHistory.BUCKET_MS
                    if (bucket != historyBucket) {
                        historyBucket = bucket
                        _history.value = history.snapshot(now)
                    }
                    applyGate()
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
        // It may have changed since the hello.
        sendControl(l, Watching(watching))
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
    private fun applyGate() {
        val open = detector.gateOpen(SystemClock.elapsedRealtime())
        if (open == _gateOpen.value) return
        _gateOpen.value = open
        audio.setMuted(!open)
    }

    private fun pollStats(l: Link) {
        val pc = l.pc ?: return
        if (link !== l) return
        pc.getStats { report -> executor.execute { if (link === l) l.stats.read(report)?.let { _videoStats.value = it } } }
        executor.schedule(STATS_INTERVAL_MS) { pollStats(l) }
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

    /** Ends [l]; while still watching, it counts as down and the next attempt is scheduled. */
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
        l.videoTrack?.let { track ->
            track.removeSink(l.frameWatch)
            sinks.forEach(track::removeSink)
        }
        l.videoTrack = null
        l.control?.unregisterObserver()
        l.control?.dispose()
        l.control = null
        l.pc?.dispose()
        l.pc = null
        _videoArriving.value = false
        _videoStats.value = null
        _level.value = null
        val t = target ?: return
        val wasUp = l.alive || _state.value is ParentState.Reconnecting
        _state.value = when {
            wasUp -> ParentState.Reconnecting(t)
            busy -> ParentState.Busy(t)
            else -> ParentState.Connecting(t)
        }
        if (wasUp) _status.value = null
        linkDown()
        // Before the alarm may ring, try every 2 s (each attempt also tries the address discovery
        // has now); afterwards back off.
        val alarmPending = lossAlarm.dueIn(SystemClock.elapsedRealtime()) != null
        val delay = when {
            busy -> BUSY_RETRY_MS
            alarmPending -> LOST_RETRY_MS
            else -> backoff.next()
        }
        retry = executor.schedule(delay) {
            retry = null
            if (link == null) attempt()
        }
    }

    /** The link is down now: the alarm rings if it stays down for the grace period. */
    private fun linkDown() {
        lossAlarm.onDown(SystemClock.elapsedRealtime())
        scheduleAlarmCheck()
    }

    private fun scheduleAlarmCheck() {
        alarmCheck?.cancel(false)
        alarmCheck = null
        val due = lossAlarm.dueIn(SystemClock.elapsedRealtime()) ?: return
        alarmCheck = executor.schedule(due) {
            alarmCheck = null
            // An attempt is under way right now: give it a few seconds before ringing.
            if (link != null && !lastChanceUsed) {
                lastChanceUsed = true
                alarmCheck = executor.schedule(LAST_CHANCE_MS) {
                    alarmCheck = null
                    ringIfStillLost()
                }
                return@schedule
            }
            ringIfStillLost()
        }
    }

    private fun ringIfStillLost() {
        _noLan.value = !hasLan()
        lossAlarm.check(SystemClock.elapsedRealtime())
        publishAlarm()
    }

    private fun publishAlarm() {
        _alarm.value = lossAlarm.state
    }

    private inner class Link(@Volatile var baby: FoundBaby) {
        /** Notes the first frame of this connection; runs on the decoder thread. */
        val frameWatch = VideoSink { if (link === this && !_videoArriving.value) _videoArriving.value = true }

        @Volatile var connection: SignalingConnection? = null
        var pc: PeerConnection? = null
        var videoTrack: VideoTrack? = null
        var control: DataChannel? = null
        val ice = PendingIce()
        val stats = VideoStatsMeter()
        var pinging = false
        var lastPong = 0L

        /** Pongs arrive: the baby answers end to end. */
        var alive = false
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
        const val LOST_RETRY_MS = 2_000L
        const val LAST_CHANCE_MS = 5_000L
    }
}
