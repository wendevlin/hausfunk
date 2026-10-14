package dev.wendelin.hausfunk.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
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
import org.webrtc.audio.JavaAudioDeviceModule

sealed interface ParentState {
    /** The baby this state is about; null while idle. */
    val babyId: String? get() = (this as? WithBaby)?.baby?.deviceId

    sealed interface WithBaby : ParentState {
        val baby: FoundBaby
    }

    data object Idle : ParentState
    data class Connecting(override val baby: FoundBaby) : WithBaby
    data class Connected(override val baby: FoundBaby) : WithBaby
    /** The baby device already serves another parent. */
    data class Busy(override val baby: FoundBaby) : WithBaby
    data class Lost(override val baby: FoundBaby) : WithBaby
}

/**
 * Parent side: receives one baby device's audio and video. Audio plays as media; video goes to
 * the sinks attached with [attach]. All WebRTC objects are touched only on the session's own
 * thread. [release] is final.
 */
class ParentSession(context: Context, identity: Identity, trustedIds: () -> Set<String>) {
    private val transport = TlsTransport(identity, trustedIds)
    private val context = context.applicationContext
    private val executor = SerialExecutor("parent-session")
    private val _state = MutableStateFlow<ParentState>(ParentState.Idle)
    val state: StateFlow<ParentState> = _state.asStateFlow()

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

    fun connect(baby: FoundBaby, ownHello: Hello) = executor.execute {
        if (released) return@execute
        link?.let { close(it, sendBye = true, lost = false) }
        val l = Link(baby)
        link = l
        _state.value = ParentState.Connecting(baby)
        audioManager.requestAudioFocus(focusRequest)
        thread(name = "parent-read") { run(l, ownHello) }
    }

    fun disconnect() = executor.execute {
        link?.let { close(it, sendBye = true, lost = false) }
        _state.value = ParentState.Idle
    }

    fun release() = executor.execute {
        if (released) return@execute
        released = true
        link?.let { close(it, sendBye = true, lost = false) }
        factory?.dispose()
        audioModule?.release()
        eglBase.release()
        executor.shutdown()
    }

    /** Call whenever the trust store changes: a removed baby is disconnected at once. */
    fun onTrustChanged(ids: Set<String>) = executor.execute {
        link?.let { if (it.baby.deviceId !in ids) close(it, sendBye = true, lost = false) }
    }

    /** Shows the video in [sink] while connected. Safe to call from any thread. */
    fun attach(sink: VideoSink) = executor.execute {
        if (sinks.add(sink)) link?.videoTrack?.addSink(sink)
    }

    fun detach(sink: VideoSink) = executor.execute {
        if (sinks.remove(sink)) link?.videoTrack?.removeSink(sink)
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

    /** Runs on the link's own thread: connects, then reads until the connection ends. */
    private fun run(l: Link, ownHello: Hello) {
        try {
            val connection = transport.connect(l.baby.address, l.baby.port, l.baby.deviceId)
            l.connection = connection
            executor.execute { if (link !== l) connection.close() }
            connection.send(ownHello)
            while (true) {
                val message = connection.receive() ?: break
                if (message is Offer) connection.setReadTimeout(0) // set up; may idle from now on
                executor.execute { if (link === l) handle(l, message) }
            }
        } catch (e: IOException) {
            Log.i(TAG, "baby connection ended: ${e.javaClass.simpleName}")
        }
        executor.execute { if (link === l) close(l, sendBye = false, lost = true) }
    }

    private fun handle(l: Link, message: SignalingMessage) {
        when (message) {
            is Hello -> if (message.version != PROTOCOL_VERSION || message.deviceId != l.baby.deviceId) {
                fail(l, "bad hello")
            }
            is Offer -> onOffer(l, message)
            is Ice -> l.pc?.let { pc -> l.ice.add(pc, IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)) }
            Busy -> {
                close(l, sendBye = false, lost = false)
                _state.value = ParentState.Busy(l.baby)
            }
            Bye -> close(l, sendBye = false, lost = true)
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
            sinks.forEach(track::addSink)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = executor.execute {
            if (link !== l) return@execute
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> _state.value = ParentState.Connected(l.baby)
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.DISCONNECTED,
                -> close(l, sendBye = true, lost = true)
                else -> Unit
            }
        }
    }

    private fun send(l: Link, message: SignalingMessage) {
        try {
            l.connection?.send(message)
        } catch (e: IOException) {
            close(l, sendBye = false, lost = true)
        }
    }

    private fun fail(l: Link, reason: String) {
        Log.w(TAG, "closing baby connection: $reason")
        if (link === l) close(l, sendBye = true, lost = true)
    }

    private fun close(l: Link, sendBye: Boolean, lost: Boolean) {
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
        l.pc?.dispose()
        l.pc = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        _state.value = if (lost) ParentState.Lost(l.baby) else ParentState.Idle
    }

    private class Link(val baby: FoundBaby) {
        @Volatile var connection: SignalingConnection? = null
        var pc: PeerConnection? = null
        var videoTrack: VideoTrack? = null
        val ice = PendingIce()
    }

    private companion object {
        const val TAG = "ParentSession"
    }
}
