package dev.wendelin.hausfunk.signaling

import dev.wendelin.hausfunk.identity.PeerClass
import dev.wendelin.hausfunk.identity.TestCerts
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TlsTransportTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val executor = Executors.newCachedThreadPool()
    private val baby = TlsTransport(TestCerts.identity("baby")) { setOf(TestCerts.PARENT_ID) }
    private val parent = TlsTransport(TestCerts.identity("parent")) { setOf(TestCerts.BABY_ID) }
    private val stranger = TlsTransport(TestCerts.identity("stranger")) { setOf(TestCerts.BABY_ID) }

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Accepts one connection and runs its handshake on another thread. */
    private fun SignalingServer.acceptOne(): Future<SignalingConnection> = executor.submit<SignalingConnection> { accept().handshake() }

    @Test
    fun pairedDevicesConnectAndKnowEachOther() {
        baby.listen { false }.use { server ->
            val incoming = server.acceptOne()
            parent.connect(loopback, server.port, TestCerts.BABY_ID).use { client ->
                val conn = incoming.get(10, TimeUnit.SECONDS)
                assertEquals(TestCerts.PARENT_ID, conn.peerDeviceId)
                assertEquals(PeerClass.TRUSTED, conn.peerClass)
                assertEquals(TestCerts.BABY_ID, client.peerDeviceId)
                client.send(Hello(PROTOCOL_VERSION, TestCerts.PARENT_ID, "Papa", "0.2.0", watching = true))
                assertEquals(Hello(PROTOCOL_VERSION, TestCerts.PARENT_ID, "Papa", "0.2.0", watching = true), conn.receive())
                conn.send(Bye)
                assertEquals(Bye, client.receive())
                conn.close()
                assertNull(client.receive())
            }
        }
    }

    @Test
    fun anUnpairedClientIsRejectedInTheHandshake() {
        baby.listen { false }.use { server ->
            val incoming = server.acceptOne()
            assertFailsWith<IOException> { stranger.connect(loopback, server.port, TestCerts.BABY_ID).receive() }
            assertFailsWith<Exception> { incoming.get(10, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun anUnpairedClientIsAPairingPeerWhileTheWindowIsOpen() {
        baby.listen { true }.use { server ->
            val incoming = server.acceptOne()
            stranger.connect(loopback, server.port, TestCerts.BABY_ID).use {
                val conn = incoming.get(10, TimeUnit.SECONDS)
                assertEquals(PeerClass.PAIRING, conn.peerClass)
                assertEquals(TestCerts.STRANGER_ID, conn.peerDeviceId)
            }
        }
    }

    @Test
    fun aPairedClientStaysTrustedWhileTheWindowIsOpen() {
        baby.listen { true }.use { server ->
            val incoming = server.acceptOne()
            parent.connect(loopback, server.port, TestCerts.BABY_ID).use {
                assertEquals(PeerClass.TRUSTED, incoming.get(10, TimeUnit.SECONDS).peerClass)
            }
        }
    }

    @Test
    fun theParentRejectsABabyItDidNotExpect() {
        // The stranger listens; the parent expects the baby's certificate.
        TlsTransport(TestCerts.identity("stranger")) { setOf(TestCerts.PARENT_ID) }.listen { false }.use { server ->
            server.acceptOne()
            assertFailsWith<IOException> { parent.connect(loopback, server.port, TestCerts.BABY_ID).receive() }
        }
    }

    @Test
    fun theParentRejectsTheExpectedBabyOnceItIsRemoved() {
        val forgetful = TlsTransport(TestCerts.identity("parent")) { emptySet() }
        baby.listen { false }.use { server ->
            server.acceptOne()
            assertFailsWith<IOException> { forgetful.connect(loopback, server.port, TestCerts.BABY_ID).receive() }
        }
    }

    @Test
    fun pairingConnectionAcceptsAnUnknownBaby() {
        val unknownBaby = TlsTransport(TestCerts.identity("baby")) { emptySet() }
        unknownBaby.listen { true }.use { server ->
            val incoming = server.acceptOne()
            stranger.connectForPairing(loopback, server.port).use { client ->
                assertEquals(TestCerts.BABY_ID, client.peerDeviceId)
                assertEquals(PeerClass.PAIRING, client.peerClass)
                assertEquals(PeerClass.PAIRING, incoming.get(10, TimeUnit.SECONDS).peerClass)
            }
        }
    }

    @Test
    fun oversizeFramesFailAfterTheHandshake() {
        baby.listen { false }.use { server ->
            val incoming = server.acceptOne()
            parent.connect(loopback, server.port, TestCerts.BABY_ID).use { client ->
                val conn = incoming.get(10, TimeUnit.SECONDS)
                // A raw length prefix beyond the limit, sent through the encrypted stream.
                val field = client.javaClass.getDeclaredField("output").apply { isAccessible = true }
                DataOutputStream(field.get(client) as java.io.OutputStream).apply {
                    writeInt(Framing.MAX_FRAME_BYTES + 1)
                    flush()
                }
                assertFailsWith<IOException> { conn.receive() }
            }
        }
    }
}
