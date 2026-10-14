package dev.wendelin.hausfunk.media

import android.content.Context
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.Executor

/** Process-wide libwebrtc setup and small helpers shared by both roles. */
object WebRtc {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        initialized = true
    }

    /** Host candidates only: no STUN, no TURN. Media never leaves the LAN. */
    fun rtcConfiguration(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

    /** An [SdpObserver] whose callbacks run on [executor]. */
    fun sdpObserver(
        executor: Executor,
        onFailure: (String) -> Unit,
        onSuccess: (SessionDescription?) -> Unit,
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = executor.execute { onSuccess(sdp) }
        override fun onSetSuccess() = executor.execute { onSuccess(null) }
        override fun onCreateFailure(error: String?) = executor.execute { onFailure(error ?: "create failed") }
        override fun onSetFailure(error: String?) = executor.execute { onFailure(error ?: "set failed") }
    }
}

/** Remote ICE candidates that arrive before the remote description is set wait here. */
class PendingIce {
    private val pending = mutableListOf<IceCandidate>()
    private var ready = false

    fun add(pc: PeerConnection, candidate: IceCandidate) {
        if (ready) pc.addIceCandidate(candidate) else pending += candidate
    }

    /** Call once the remote description is set. */
    fun flush(pc: PeerConnection) {
        ready = true
        pending.forEach { pc.addIceCandidate(it) }
        pending.clear()
    }
}

/** Default no-op callbacks, so sessions only override what they use. */
abstract class PeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
    override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
}
