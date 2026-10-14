package io.github.wendevlin.hausfunk.identity

import java.security.MessageDigest

/** Device IDs are the lowercase hex SHA-256 fingerprint of a certificate's DER encoding. */
object Fingerprint {
    fun sha256(der: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(der)

    fun deviceId(der: ByteArray): String = sha256(der).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
