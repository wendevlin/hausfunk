package dev.wendelin.hausfunk.identity

import android.annotation.SuppressLint
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Always presents the device identity, whatever issuers the peer asks for (with self-signed
 * certificates that list is empty). The private key stays in the Keystore; TLS only signs with it.
 */
class IdentityKeyManager(private val privateKey: PrivateKey, private val certificate: X509Certificate) :
    X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(certificate) else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) privateKey else null

    private companion object {
        const val ALIAS = "identity"
    }
}

/**
 * Trust is pinning: a peer is accepted if and only if [accept] says yes to the SHA-256 fingerprint
 * (device ID) of its certificate. Validity dates, chains, issuers and host names are ignored on
 * purpose. [accept] is asked during every handshake, so it always sees the current trust store and
 * pairing window. Lint's CustomX509TrustManager warning is about trust managers that accept
 * everything; this one replaces CA validation with stricter pinning on purpose (docs/PLAN.md).
 */
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager(private val accept: (deviceId: String) -> Boolean) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        if (!accept(Fingerprint.deviceId(leaf.encoded))) throw CertificateException("certificate not paired")
    }
}

/** How the baby classifies a parent after the handshake. Never TRUSTED for an unknown certificate. */
enum class PeerClass { TRUSTED, PAIRING, REJECTED }

/**
 * The baby's decision for a client certificate: pinned devices are TRUSTED; an unknown device is
 * PAIRING only while the pairing window is open; anything else is REJECTED. The server trust
 * manager and the post-handshake classification both use this, with fresh values each time.
 */
fun classifyPeer(deviceId: String, trusted: Set<String>, pairingWindowOpen: Boolean): PeerClass = when {
    deviceId in trusted -> PeerClass.TRUSTED
    pairingWindowOpen -> PeerClass.PAIRING
    else -> PeerClass.REJECTED
}
