package dev.wendelin.hausfunk.pairing

import android.os.SystemClock
import android.util.Log
import dev.wendelin.hausfunk.SerialExecutor
import dev.wendelin.hausfunk.signaling.SignalingConnection
import dev.wendelin.hausfunk.signaling.SignalingMessage
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Future
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the pairing screen shows. */
sealed interface PairingState {
    data object Connecting : PairingState

    /**
     * [confirmed]: this side tapped "They match" and waits for the other phone. [deadline]:
     * `SystemClock.elapsedRealtime()` at which the exchange gives up.
     */
    data class Comparing(
        val emojis: List<SasEmoji>,
        val peerName: String,
        val confirmed: Boolean,
        val deadline: Long,
    ) : PairingState

    data class Paired(val peerName: String) : PairingState

    data object Failed : PairingState
}

/** The peer to store after both confirmations. */
class PairedPeer(val deviceId: String, val name: String, val certificate: ByteArray)

/**
 * Runs one pairing exchange over one connection, for either role: feeds received messages to
 * [PairingProtocol], sends what it asks for and publishes [state]. Stores nothing itself: [onPaired]
 * is called once, only after both sides confirmed. Everything else ends in [PairingState.Failed].
 */
class PairingRunner(
    private val connection: SignalingConnection,
    isParent: Boolean,
    ownDeviceId: String,
    ownName: String,
    ownFingerprint: ByteArray,
    private val onPaired: (PairedPeer) -> Unit,
    /** Called once when the exchange ended, successfully or not. */
    private val onFinished: () -> Unit = {},
) {
    private val executor = SerialExecutor("pairing")
    private val protocol = PairingProtocol(
        isParent,
        ByteArray(Sas.NONCE_BYTES).also { SecureRandom().nextBytes(it) },
        ownDeviceId,
        ownName,
        ownFingerprint,
        connection.peerFingerprint,
    )
    private val _state = MutableStateFlow<PairingState>(PairingState.Connecting)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private var timeout: Future<*>? = null
    private var deadline = 0L
    private var ended = false

    /** The emojis were shown at some point; a request that ends earlier is not worth showing. */
    @Volatile var reachedCompare = false
        private set

    /** [firstMessage]: already read from the connection by the baby, which dispatches on it. */
    fun start(firstMessage: SignalingMessage? = null) = executor.execute {
        // Waiting for the other user may take long; the exchange's own timeout covers it.
        connection.setReadTimeout(0)
        deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        timeout = executor.schedule(TIMEOUT_MS) { apply(protocol.reject("timeout")) }
        apply(protocol.start())
        if (firstMessage != null) apply(protocol.onMessage(firstMessage))
        thread(name = "pairing-read") { readLoop() }
    }

    /** "They match". */
    fun confirm() = executor.execute {
        val steps = protocol.confirm()
        val current = _state.value
        // Show that this phone now waits for the other one.
        if (steps.isNotEmpty() && current is PairingState.Comparing) _state.value = current.copy(confirmed = true)
        apply(steps)
    }

    /** "Don't match", back, or the screen went away. */
    fun cancel() = executor.execute { apply(protocol.reject("cancelled")) }

    private fun readLoop() {
        try {
            while (true) {
                val message = connection.receive() ?: break
                executor.execute { apply(protocol.onMessage(message)) }
            }
        } catch (e: IOException) {
            Log.i(TAG, "pairing connection ended: ${e.javaClass.simpleName}")
        }
        // A dropped connection stores nothing.
        executor.execute { end(PairingState.Failed) }
    }

    private fun apply(steps: List<PairingStep>) {
        for (step in steps) {
            if (ended) return
            when (step) {
                is PairingStep.Send -> try {
                    connection.send(step.message)
                } catch (e: IOException) {
                    end(PairingState.Failed)
                }
                is PairingStep.Compare -> {
                    reachedCompare = true
                    _state.value = PairingState.Comparing(step.emojis, step.peerName, confirmed = false, deadline = deadline)
                }
                is PairingStep.Paired -> {
                    onPaired(PairedPeer(connection.peerDeviceId, step.peerName, connection.peerCertificate))
                    end(PairingState.Paired(step.peerName))
                }
                is PairingStep.Abort -> {
                    Log.i(TAG, "pairing aborted") // the reason may describe peer input; not logged
                    end(PairingState.Failed)
                }
            }
        }
    }

    private fun end(final: PairingState) {
        if (ended) return
        ended = true
        timeout?.cancel(false)
        _state.value = final
        connection.close()
        onFinished()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "Pairing"
        /** Also the length of the countdown bar on the pairing screen. */
        const val TIMEOUT_MS = 120_000L
    }
}
