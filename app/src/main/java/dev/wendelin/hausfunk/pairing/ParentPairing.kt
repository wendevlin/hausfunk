package dev.wendelin.hausfunk.pairing

import dev.wendelin.hausfunk.discovery.FoundBaby
import dev.wendelin.hausfunk.identity.Fingerprint
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.signaling.TlsTransport
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Parent side of one pairing: connects to a baby whose window is open, accepting its unknown
 * certificate for this one connection, then runs the exchange. A baby that is already paired, or
 * whose certificate does not match the ID it announced, is refused.
 */
class ParentPairing(
    private val identity: Identity,
    private val ownName: String,
    private val trustedIds: () -> Set<String>,
    /** Pins the socket to the local network (see LanNetworks). */
    private val bind: (Socket, InetAddress) -> Unit,
    private val onPaired: (PairedPeer) -> Unit,
) {
    private val transport = TlsTransport(identity, trustedIds)
    private val _state = MutableStateFlow<PairingState>(PairingState.Connecting)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    @Volatile private var runner: PairingRunner? = null

    @Volatile private var cancelled = false

    fun start(baby: FoundBaby, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val connection = try {
                transport.connectForPairing(baby.address, baby.port) { bind(it, baby.address) }
            } catch (e: IOException) {
                _state.value = PairingState.Failed
                return@launch
            }
            val id = connection.peerDeviceId
            if (cancelled || id != baby.deviceId || id in trustedIds()) {
                connection.close()
                _state.value = PairingState.Failed
                return@launch
            }
            val r = PairingRunner(
                connection,
                isParent = true,
                ownDeviceId = identity.deviceId,
                ownName = ownName,
                ownFingerprint = Fingerprint.sha256(identity.certificate.encoded),
                onPaired = onPaired,
            )
            runner = r
            r.start()
            if (cancelled) r.cancel()
            r.state.takeWhile { it !is PairingState.Paired && it != PairingState.Failed }.collect { _state.value = it }
            _state.value = r.state.value
        }
    }

    fun confirm() {
        runner?.confirm()
    }

    fun cancel() {
        cancelled = true
        runner?.cancel()
    }
}
