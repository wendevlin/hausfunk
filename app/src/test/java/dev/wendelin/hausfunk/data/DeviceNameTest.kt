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

    @Test
    fun serviceNamesFitADnsLabel() {
        assertEquals("Kinderzimmer", DeviceName.serviceName(" Kinderzimmer "))
        assertEquals("Hausfunk", DeviceName.serviceName("   "))
        // 32 three-byte characters are 96 bytes: cut to 21 whole characters (63 bytes).
        assertEquals("日".repeat(21), DeviceName.serviceName("日".repeat(32)))
        // Emoji are four bytes and never split.
        assertEquals("😀".repeat(15), DeviceName.serviceName("😀".repeat(16)))
    }
}
