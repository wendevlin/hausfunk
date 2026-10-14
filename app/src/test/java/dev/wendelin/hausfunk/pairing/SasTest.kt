package dev.wendelin.hausfunk.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Expected values were computed once with an independent Python implementation. */
class SasTest {
    private val parentNonce = ByteArray(32) { it.toByte() }
    private val babyNonce = ByteArray(32) { (it + 32).toByte() }
    private val parentFp = hex("281102d380ceb48035a098cec401e33914fc92b6fd9f1aca35bdb99432042e91")
    private val babyFp = hex("514ef02777a3b6beb98a214b53be6a1151eff08c896525d34d57ea64fc7d19c4")

    @Test
    fun fixedVectors() {
        assertEquals(listOf(14, 38, 53, 42, 40, 44, 8), Sas.emojiIndices(parentNonce, babyNonce, parentFp, babyFp))
        assertEquals(
            listOf(4, 26, 53, 4, 51, 21, 45),
            Sas.emojiIndices(ByteArray(32), ByteArray(32), ByteArray(32), ByteArray(32) { -1 }),
        )
        val names = Sas.emojiIndices(parentNonce, babyNonce, parentFp, babyFp).map { SasEmojis.all[it].english }
        assertEquals(listOf("Butterfly", "Hourglass", "Aeroplane", "Book", "Gift", "Paperclip", "Panda"), names)
    }

    @Test
    fun swappingFingerprintsChangesTheEmojis() {
        assertEquals(listOf(44, 63, 31, 4, 21, 61, 7), Sas.emojiIndices(parentNonce, babyNonce, babyFp, parentFp))
    }

    @Test
    fun changingAnyByteOfAnyInputChangesTheEmojis() {
        val base = Sas.emojiIndices(parentNonce, babyNonce, parentFp, babyFp)
        val inputs = listOf(parentNonce, babyNonce, parentFp, babyFp)
        for (which in inputs.indices) {
            for (byte in 0 until 32) {
                val changed = inputs.map { it.copyOf() }
                changed[which][byte] = (changed[which][byte] + 1).toByte()
                val indices = Sas.emojiIndices(changed[0], changed[1], changed[2], changed[3])
                assertNotEquals(base, indices, "input $which byte $byte")
            }
        }
    }

    @Test
    fun commitmentOpensOnlyWithItsNonce() {
        val commitment = Sas.commitment(parentNonce)
        assertEquals("ef964fbc7f400b9ab7f50b5eba7874b8cbcad26b5092c05258263d64577427bd", commitment.toHexString())
        assertTrue(Sas.opens(commitment, parentNonce))
        assertFalse(Sas.opens(commitment, babyNonce))
        assertFalse(Sas.opens(commitment, parentNonce.copyOf(31)))
    }

    @Test
    fun tableHas64DistinctEmojisWithGermanNames() {
        assertEquals(64, SasEmojis.all.size)
        assertEquals(64, SasEmojis.all.map { it.emoji }.toSet().size)
        assertTrue(SasEmojis.all.all { it.english.isNotBlank() && it.german.isNotBlank() })
        assertEquals(SasEmoji("🐶", "Dog", "Hund"), SasEmojis.all[0])
        assertEquals(SasEmoji("📌", "Pin", "Stecknadel"), SasEmojis.all[63])
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
