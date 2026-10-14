package dev.wendelin.hausfunk.signaling

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FramingTest {
    private fun frame(length: Int, body: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()) + body

    @Test
    fun roundTripsFrames() {
        val out = ByteArrayOutputStream()
        Framing.write(out, """{"type":"bye"}""")
        Framing.write(out, "Kinderzimmer 👶")
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals("""{"type":"bye"}""", Framing.read(input))
        assertEquals("Kinderzimmer 👶", Framing.read(input))
        assertNull(Framing.read(input))
    }

    @Test
    fun acceptsTheMaximumSize() {
        val text = "a".repeat(Framing.MAX_FRAME_BYTES)
        val out = ByteArrayOutputStream()
        Framing.write(out, text)
        assertEquals(text, Framing.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun rejectsOversizeFramesOnWrite() {
        assertFailsWith<FrameException> {
            Framing.write(ByteArrayOutputStream(), "a".repeat(Framing.MAX_FRAME_BYTES + 1))
        }
    }

    @Test
    fun rejectsOversizeLengthOnRead() {
        assertFailsWith<FrameException> {
            Framing.read(ByteArrayInputStream(frame(Framing.MAX_FRAME_BYTES + 1)))
        }
        assertFailsWith<FrameException> { Framing.read(ByteArrayInputStream(frame(-1))) }
    }

    @Test
    fun rejectsEmptyFrames() {
        assertFailsWith<FrameException> { Framing.read(ByteArrayInputStream(frame(0))) }
        assertFailsWith<FrameException> { Framing.write(ByteArrayOutputStream(), "") }
    }

    @Test
    fun rejectsTruncatedFrames() {
        assertFailsWith<EOFException> { Framing.read(ByteArrayInputStream(frame(10, "abc".toByteArray()))) }
        assertFailsWith<EOFException> { Framing.read(ByteArrayInputStream(byteArrayOf(0, 0))) }
    }

    @Test
    fun rejectsInvalidUtf8() {
        assertFailsWith<FrameException> {
            Framing.read(ByteArrayInputStream(frame(2, byteArrayOf(0xC3.toByte(), 0x28))))
        }
    }
}
