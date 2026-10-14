package dev.wendelin.hausfunk.identity

import java.security.PrivateKey
import java.security.cert.X509Certificate

/** The device's permanent identity: a non-exportable EC P-256 key in the Android Keystore. */
class Identity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val deviceId: String = Fingerprint.deviceId(certificate.encoded)
}
