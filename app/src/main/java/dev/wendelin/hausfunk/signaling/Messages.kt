package dev.wendelin.hausfunk.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire protocol version, sent in [Hello]. */
const val PROTOCOL_VERSION = 1

/** Messages on the signaling connection. See docs/PROTOCOL.md. */
@Serializable
sealed interface SignalingMessage

@Serializable
@SerialName("hello")
data class Hello(
    val version: Int,
    val deviceId: String,
    val name: String,
    val appVersion: String,
    /** Sent by the parent only. */
    val watching: Boolean? = null,
) : SignalingMessage

@Serializable
@SerialName("offer")
data class Offer(val sdp: String) : SignalingMessage

@Serializable
@SerialName("answer")
data class Answer(val sdp: String) : SignalingMessage

@Serializable
@SerialName("ice")
data class Ice(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int) : SignalingMessage

@Serializable
@SerialName("busy")
data object Busy : SignalingMessage

@Serializable
@SerialName("bye")
data object Bye : SignalingMessage

/** "I do not (or no longer) trust you": the sender removed the pairing. Ends the connection. */
@Serializable
@SerialName("unpair")
data object Unpair : SignalingMessage

@Serializable
@SerialName("pair_commit")
data class PairCommit(val commitment: String) : SignalingMessage

@Serializable
@SerialName("pair_reveal")
data class PairReveal(val nonce: String, val deviceId: String, val name: String) : SignalingMessage

@Serializable
@SerialName("pair_confirm")
data object PairConfirm : SignalingMessage

@Serializable
@SerialName("pair_abort")
data object PairAbort : SignalingMessage

/** Messages on the WebRTC data channel `control`. */
@Serializable
sealed interface ControlMessage

@Serializable
@SerialName("watching")
data class Watching(val watching: Boolean) : ControlMessage

@Serializable
@SerialName("status")
data class Status(
    val battery: Int,
    val charging: Boolean,
    val parents: Int,
    val thermalReduced: Boolean,
) : ControlMessage

@Serializable
@SerialName("level")
data class Level(val dbfs: Float) : ControlMessage

@Serializable
@SerialName("ping")
data object Ping : ControlMessage

@Serializable
@SerialName("pong")
data object Pong : ControlMessage
