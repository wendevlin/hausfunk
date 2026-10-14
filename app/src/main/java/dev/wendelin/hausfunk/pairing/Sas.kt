package dev.wendelin.hausfunk.pairing

import java.security.MessageDigest

/** Commitments and the short authentication string (SAS). See docs/PROTOCOL.md, "Pairing". */
object Sas {
    const val NONCE_BYTES = 32
    const val FINGERPRINT_BYTES = 32
    const val EMOJI_COUNT = 7
    private val COMMIT_LABEL = "babyphone-commit-v1".toByteArray(Charsets.US_ASCII)
    private val SAS_LABEL = "babyphone-sas-v1".toByteArray(Charsets.US_ASCII)

    /** SHA-256("babyphone-commit-v1" || nonce). */
    fun commitment(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "nonce size" }
        return sha256(COMMIT_LABEL, nonce)
    }

    /** Constant-time check that [nonce] is the one behind [commitment]. */
    fun opens(commitment: ByteArray, nonce: ByteArray): Boolean =
        nonce.size == NONCE_BYTES && MessageDigest.isEqual(commitment, commitment(nonce))

    /**
     * The 7 emoji indices: SHA-256("babyphone-sas-v1" || nP || nB || fpP || fpB), first 42 bits as a
     * big-endian number, split into 7 groups of 6 bits. P is the parent, B the baby.
     */
    fun emojiIndices(parentNonce: ByteArray, babyNonce: ByteArray, parentFp: ByteArray, babyFp: ByteArray): List<Int> {
        require(parentNonce.size == NONCE_BYTES && babyNonce.size == NONCE_BYTES) { "nonce size" }
        require(parentFp.size == FINGERPRINT_BYTES && babyFp.size == FINGERPRINT_BYTES) { "fingerprint size" }
        val hash = sha256(SAS_LABEL, parentNonce, babyNonce, parentFp, babyFp)
        var bits = 0L
        for (i in 0 until 6) bits = (bits shl 8) or (hash[i].toLong() and 0xff)
        bits = bits ushr 6 // 48 bits read, keep the first 42
        return (0 until EMOJI_COUNT).map { i -> ((bits ushr (6 * (EMOJI_COUNT - 1 - i))) and 0x3f).toInt() }
    }

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach(::update)
            digest()
        }
}
