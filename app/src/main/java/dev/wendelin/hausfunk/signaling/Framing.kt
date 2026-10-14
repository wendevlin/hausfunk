package dev.wendelin.hausfunk.signaling

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Thrown for a frame that must close the connection: oversize, empty or not UTF-8. */
class FrameException(message: String) : IOException(message)

/** Frames: 4-byte big-endian length, then that many bytes of UTF-8 JSON. Max 64 KiB. */
object Framing {
    const val MAX_FRAME_BYTES = 64 * 1024

    fun write(out: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) throw FrameException("frame size ${bytes.size}")
        val data = DataOutputStream(out)
        data.writeInt(bytes.size)
        data.write(bytes)
        data.flush()
    }

    /** Returns the next frame, or null on a clean end of stream between frames. */
    fun read(input: InputStream): String? {
        val data = DataInputStream(input)
        val first = data.read()
        if (first < 0) return null
        val length = (first shl 24) or (data.readUnsignedByte() shl 16) or
            (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
        if (length <= 0 || length > MAX_FRAME_BYTES) throw FrameException("frame size $length")
        val bytes = ByteArray(length)
        data.readFully(bytes) // EOFException on a truncated frame
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw FrameException("frame is not UTF-8")
        }
    }
}
