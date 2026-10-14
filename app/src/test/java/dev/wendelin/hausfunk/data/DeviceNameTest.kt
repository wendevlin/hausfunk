package dev.wendelin.hausfunk.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Names arrive from the network later; the cap and the control-character filter must hold. */
class DeviceNameTest {
    @Test
    fun capsAtMaxLength() {
        assertEquals("a".repeat(32), DeviceName.sanitize("a".repeat(40)))
    }

    @Test
    fun neverSplitsASurrogatePair() {
        val name = "a".repeat(31) + "👶" // 31 letters + baby emoji (2 chars)
        assertEquals("a".repeat(31), DeviceName.sanitize(name))
    }

    @Test
    fun removesControlCharacters() {
        assertEquals("Kinderzimmer", DeviceName.sanitize("Kinder\nzim\u0000mer\u001b"))
    }

    @Test
    fun blankNamesAreInvalid() {
        assertFalse(DeviceName.isValid("   "))
        assertFalse(DeviceName.isValid("\n\t"))
        assertTrue(DeviceName.isValid(" Kinderzimmer "))
    }
}
