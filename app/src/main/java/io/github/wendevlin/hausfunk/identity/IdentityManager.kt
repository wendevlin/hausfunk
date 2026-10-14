package io.github.wendevlin.hausfunk.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.wendevlin.hausfunk.data.SettingsRepository
import io.github.wendevlin.hausfunk.data.TrustStoreRepository
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
import javax.security.auth.x500.X500Principal

/** The device's permanent identity: a non-exportable EC P-256 key in the Android Keystore. */
class Identity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val deviceId: String = Fingerprint.deviceId(certificate.encoded)
}

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
        // Peers pinned the old certificate, so every pairing is void.
        val lost = previousId != null
        if (lost) trustStore.clear()
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

    /** A key can exist yet be unusable after an OS update; one test signature finds out. */
    private fun canSign(key: PrivateKey): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(byteArrayOf(0))
            sign()
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
            .setDigests(KeyProperties.DIGEST_SHA256)
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
