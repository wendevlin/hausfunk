package dev.wendelin.hausfunk.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.wendelin.hausfunk.data.SettingsRepository
import dev.wendelin.hausfunk.data.TrustStoreRepository
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import javax.security.auth.x500.X500Principal

class IdentityManager(
    private val settings: SettingsRepository,
    private val trustStore: TrustStoreRepository,
) {
    /**
     * Loads the identity, creating it on first launch. If a previously created key is missing or
     * unusable, a new one is generated, the trust store is cleared and the user is told that every
     * device must be paired again. There is never a software-key fallback. Blocking; call off the
     * main thread.
     */
    suspend fun ensureIdentity(): Identity {
        val previousId = settings.current().identityDeviceId
        val existing = loadUsable()
        if (existing != null && existing.deviceId == previousId) return existing

        val identity = existing ?: generate()
        // Peers pinned the old certificate, so every pairing is void. Keys from before phase 3
        // lack the raw-digest purpose TLS needs; with nothing paired yet, replacing one is silent.
        val lost = previousId != null && trustStore.devices.first().isNotEmpty()
        if (previousId != null) trustStore.clear()
        settings.setIdentity(identity.deviceId, resetNotice = lost)
        return identity
    }

    private fun loadUsable(): Identity? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(ALIAS, null) as? PrivateKey
        val cert = keyStore.getCertificate(ALIAS) as? X509Certificate
        if (key != null && cert != null && canSign(key)) Identity(key, cert) else null
    } catch (e: GeneralSecurityException) {
        null
    }

    /**
     * A key can exist yet be unusable after an OS update; test signatures find out. Both the
     * SHA-256 and the raw-digest form are needed: TLS uses the raw one.
     */
    private fun canSign(key: PrivateKey): Boolean = try {
        for (algorithm in listOf("SHA256withECDSA", "NONEwithECDSA")) {
            Signature.getInstance(algorithm).run {
                initSign(key)
                update(ByteArray(32))
                sign()
            }
        }
        true
    } catch (e: GeneralSecurityException) {
        false
    }

    private fun generate(): Identity {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)

        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS signs a digest it computed itself, so the platform asks the Keystore for NONEwithECDSA.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=babyphone"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(0))
            // Validity is never checked; the end date is simply as far out as X.509 allows.
            .setCertificateNotAfter(
                GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { set(9999, Calendar.DECEMBER, 31) }.time,
            )
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
        return checkNotNull(loadUsable()) { "Keystore did not return the new identity" }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "identity"
    }
}
