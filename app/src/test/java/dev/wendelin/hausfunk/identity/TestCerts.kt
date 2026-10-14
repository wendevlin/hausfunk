package dev.wendelin.hausfunk.identity

import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** Pre-generated test identities from src/test/resources/certs (see the README there). */
object TestCerts {
    private fun bytes(name: String): ByteArray =
        checkNotNull(TestCerts::class.java.getResourceAsStream("/certs/$name")) { "missing $name" }.readBytes()

    fun certificate(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(bytes("$name.der").inputStream()) as X509Certificate

    fun identity(name: String): Identity = Identity(
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes("$name.key.der"))),
        certificate(name),
    )

    const val BABY_ID = "514ef02777a3b6beb98a214b53be6a1151eff08c896525d34d57ea64fc7d19c4"
    const val PARENT_ID = "281102d380ceb48035a098cec401e33914fc92b6fd9f1aca35bdb99432042e91"
    const val STRANGER_ID = "2ff36c5b3e6fbe1f0eef7a71fa51a5fd9f2bd3e87f61087d63df78bb47adbdfb"
}
