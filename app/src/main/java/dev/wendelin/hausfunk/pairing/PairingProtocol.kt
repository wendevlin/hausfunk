package dev.wendelin.hausfunk.pairing

import dev.wendelin.hausfunk.data.DeviceName
import dev.wendelin.hausfunk.identity.Fingerprint
import dev.wendelin.hausfunk.signaling.PairAbort
import dev.wendelin.hausfunk.signaling.PairCommit
import dev.wendelin.hausfunk.signaling.PairConfirm
import dev.wendelin.hausfunk.signaling.PairReveal
import dev.wendelin.hausfunk.signaling.SignalingMessage
import java.util.Base64

/** What the caller has to do after feeding the protocol a message or a user decision. */
sealed interface PairingStep {
    data class Send(val message: SignalingMessage) : PairingStep

    /** Show the emojis and ask "They match?" */
    data class Compare(val emojis: List<SasEmoji>, val peerName: String) : PairingStep

    /** Both sides confirmed: store the peer now, then close. */
    data class Paired(val peerName: String) : PairingStep

    /** Store nothing and close. */
    data class Abort(val reason: String) : PairingStep
}

/**
 * The commit-reveal pairing exchange inside an already established TLS connection, for one side.
 * Pure logic, no I/O: the caller sends what comes out and shows the emojis. The parent starts.
 * Any deviation from the sequence aborts; nothing is ever stored without both confirmations.
 */
class PairingProtocol(
    private val isParent: Boolean,
    private val ownNonce: ByteArray,
    private val ownDeviceId: String,
    private val ownName: String,
    /** SHA-256 of our own and of the peer's certificate, both taken from the TLS session. */
    private val ownFingerprint: ByteArray,
    private val peerFingerprint: ByteArray,
) {
    private var sentCommit = false
    private var peerCommitment: ByteArray? = null
    private var sentReveal = false
    private var peerNonce: ByteArray? = null
    private var peerName = ""
    private var comparing = false
    private var localConfirmed = false
    private var peerConfirmed = false
    private var finished = false

    init {
        require(ownNonce.size == Sas.NONCE_BYTES) { "nonce size" }
    }

    /** The parent sends its commitment first; the baby waits for it. */
    fun start(): List<PairingStep> = if (isParent) listOf(sendCommit()) else emptyList()

    fun onMessage(message: SignalingMessage): List<PairingStep> {
        if (finished) return emptyList()
        return when (message) {
            is PairCommit -> onCommit(message)
            is PairReveal -> onReveal(message)
            PairConfirm -> onPeerConfirm()
            PairAbort -> end("peer aborted", sendAbort = false)
            else -> end("unexpected ${message.javaClass.simpleName}")
        }
    }

    /** The user tapped "They match". */
    fun confirm(): List<PairingStep> {
        if (finished || !comparing || localConfirmed) return emptyList()
        localConfirmed = true
        val steps = mutableListOf<PairingStep>(PairingStep.Send(PairConfirm))
        if (peerConfirmed) steps += pair()
        return steps
    }

    /** The user tapped "Don't match", went back, or the time ran out. */
    fun reject(reason: String): List<PairingStep> = if (finished) emptyList() else end(reason)

    private fun onCommit(message: PairCommit): List<PairingStep> {
        if (peerCommitment != null) return end("second commitment")
        val commitment = decode(message.commitment) ?: return end("bad commitment")
        if (commitment.size != Sas.NONCE_BYTES) return end("bad commitment")
        peerCommitment = commitment
        return if (isParent) {
            // The baby has committed; only now may the parent reveal its nonce.
            if (!sentCommit) return end("commitment before ours")
            listOf(sendReveal())
        } else {
            listOf(sendCommit())
        }
    }

    private fun onReveal(message: PairReveal): List<PairingStep> {
        val commitment = peerCommitment ?: return end("reveal before commitment")
        if (peerNonce != null) return end("second reveal")
        if (isParent && !sentReveal) return end("reveal before ours")
        val nonce = decode(message.nonce) ?: return end("bad nonce")
        if (!Sas.opens(commitment, nonce)) return end("nonce does not match commitment")
        if (message.deviceId != Fingerprint.toHex(peerFingerprint)) return end("device ID does not match certificate")
        peerNonce = nonce
        peerName = DeviceName.sanitize(message.name)
        val steps = mutableListOf<PairingStep>()
        if (!isParent) steps += sendReveal()
        comparing = true
        steps += PairingStep.Compare(emojis(nonce), peerName)
        return steps
    }

    private fun onPeerConfirm(): List<PairingStep> {
        if (!comparing || peerConfirmed) return end("confirmation out of order")
        peerConfirmed = true
        return if (localConfirmed) pair() else emptyList()
    }

    private fun emojis(peerNonce: ByteArray): List<SasEmoji> {
        val indices = if (isParent) {
            Sas.emojiIndices(ownNonce, peerNonce, ownFingerprint, peerFingerprint)
        } else {
            Sas.emojiIndices(peerNonce, ownNonce, peerFingerprint, ownFingerprint)
        }
        return indices.map { SasEmojis.all[it] }
    }

    private fun sendCommit(): PairingStep {
        sentCommit = true
        return PairingStep.Send(PairCommit(encode(Sas.commitment(ownNonce))))
    }

    private fun sendReveal(): PairingStep {
        sentReveal = true
        return PairingStep.Send(PairReveal(encode(ownNonce), ownDeviceId, ownName))
    }

    private fun pair(): List<PairingStep> {
        finished = true
        return listOf(PairingStep.Paired(peerName))
    }

    private fun end(reason: String, sendAbort: Boolean = true): List<PairingStep> {
        finished = true
        return if (sendAbort) listOf(PairingStep.Send(PairAbort), PairingStep.Abort(reason)) else listOf(PairingStep.Abort(reason))
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}
