package dev.wendelin.hausfunk.signaling

import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlainTcpTransportTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun exchangesMessagesAndSkipsUnknownTypes() {
        val transport = PlainTcpTransport()
        transport.listen().use { server ->
            var received: SignalingMessage? = null
            val baby = thread {
                server.accept().use { conn ->
                    received = conn.receive()
                    conn.send(Offer("sdp"))
                }
            }
            transport.connect(loopback, server.port).use { conn ->
                conn.send(Hello(PROTOCOL_VERSION, "id", "Parent", "0.2.0", watching = true))
                assertEquals(Offer("sdp"), conn.receive())
                assertNull(conn.receive()) // baby closed cleanly
            }
            baby.join()
            assertEquals(Hello(PROTOCOL_VERSION, "id", "Parent", "0.2.0", watching = true), received)
        }
    }

    @Test
    fun unknownTypesAreSkipped() {
        PlainTcpTransport().listen().use { server ->
            val sender = thread {
                Socket(loopback, server.port).use { socket ->
                    val out = socket.getOutputStream()
                    Framing.write(out, """{"type":"future"}""")
                    Framing.write(out, """{"type":"bye"}""")
                }
            }
            server.accept().use { conn -> assertEquals(Bye, conn.receive()) }
            sender.join()
        }
    }

    @Test
    fun malformedOrOversizeFramesFail() {
        PlainTcpTransport().listen().use { server ->
            val sender = thread {
                Socket(loopback, server.port).use { socket ->
                    Framing.write(socket.getOutputStream(), """{"type":"offer"}""")
                    DataOutputStream(socket.getOutputStream()).writeInt(Framing.MAX_FRAME_BYTES + 1)
                }
            }
            server.accept().use { conn -> assertFailsWith<IOException> { conn.receive() } }
            sender.join()
        }
    }
}
