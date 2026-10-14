package dev.wendelin.hausfunk.signaling

import dev.wendelin.hausfunk.identity.Fingerprint
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.identity.IdentityKeyManager
import dev.wendelin.hausfunk.identity.PeerClass
import dev.wendelin.hausfunk.identity.PinningTrustManager
import dev.wendelin.hausfunk.identity.classifyPeer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Signaling over TLS 1.3 with mutual authentication and certificate pinning. Both sides present
 * their Keystore identity. Nothing is ever accepted without a pinned fingerprint, except pairing
 * connections: on the baby an unknown parent may connect only to ask for pairing, on the parent
 * when the user taps an unpaired baby.
 */
class TlsTransport(
    private val identity: Identity,
    /** Device IDs in the trust store, read fresh for every handshake. */
    private val trustedIds: () -> Set<String>,
) {
    private val keyManager = IdentityKeyManager(identity.privateKey, identity.certificate)

    /**
     * Baby side. A client certificate passes the handshake if it is pinned, or while
     * [allowPairing] says yes; after the handshake the peer is classified again with fresh values.
     * An unknown peer can only ever reach the pairing exchange, never signaling.
     */
    fun listen(allowPairing: () -> Boolean): SignalingServer {
        val trust = PinningTrustManager { classifyPeer(it, trustedIds(), allowPairing()) != PeerClass.REJECTED }
        val serverSocket = context(trust).serverSocketFactory.createServerSocket(0) as SSLServerSocket
        serverSocket.enabledProtocols = PROTOCOLS
        serverSocket.needClientAuth = true
        return Server(serverSocket, allowPairing)
    }

    /**
     * Parent side: connects to a paired baby; anything but [expectedDeviceId] is rejected.
     * [connectTimeoutMs] limits only the TCP connect, so a stale address fails fast.
     */
    fun connect(
        address: InetAddress,
        port: Int,
        expectedDeviceId: String,
        connectTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
    ): SignalingConnection {
        val trust = PinningTrustManager { it == expectedDeviceId && it in trustedIds() }
        return connect(address, port, trust, PeerClass.TRUSTED, connectTimeoutMs)
    }

    /**
     * Parent side, pairing only: accepts whatever certificate the baby presents. The emoji
     * comparison is what authenticates it; this context serves this one connection.
     */
    fun connectForPairing(address: InetAddress, port: Int): SignalingConnection =
        connect(address, port, PinningTrustManager { true }, PeerClass.PAIRING, HANDSHAKE_TIMEOUT_MS)

    private fun connect(
        address: InetAddress,
        port: Int,
        trust: X509ExtendedTrustManager,
        peerClass: PeerClass,
        connectTimeoutMs: Int,
    ): SignalingConnection {
        val socket = context(trust).socketFactory.createSocket() as SSLSocket
        try {
            socket.enabledProtocols = PROTOCOLS
            socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            return handshake(socket, peerClass)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    private fun context(trust: X509ExtendedTrustManager): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), arrayOf(trust), SecureRandom()) }

    private inner class Server(
        private val serverSocket: SSLServerSocket,
        private val allowPairing: () -> Boolean,
    ) : SignalingServer {
        override val port: Int get() = serverSocket.localPort

        override fun accept(): SignalingServer.Pending {
            val socket = serverSocket.accept() as SSLSocket
            return object : SignalingServer.Pending {
                override fun handshake(): SignalingConnection = try {
                    handshake(socket) { deviceId -> classifyPeer(deviceId, trustedIds(), allowPairing()) }
                } catch (e: IOException) {
                    socket.close()
                    throw e
                }

                override fun close() = socket.close()
            }
        }

        override fun close() = serverSocket.close()
    }

    private fun handshake(socket: SSLSocket, peerClass: PeerClass): SignalingConnection = handshake(socket) { peerClass }

    private fun handshake(socket: SSLSocket, classify: (String) -> PeerClass): SignalingConnection {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket.tcpNoDelay = true
        socket.startHandshake()
        val session = socket.session
        if (session.protocol != "TLSv1.3") throw IOException("not TLS 1.3")
        val certificate = session.peerCertificates.firstOrNull() as? X509Certificate ?: throw IOException("no peer certificate")
        val der = certificate.encoded
        val deviceId = Fingerprint.deviceId(der)
        val peerClass = classify(deviceId)
        if (peerClass == PeerClass.REJECTED) throw IOException("peer not paired")
        socket.soTimeout = SETUP_READ_TIMEOUT_MS
        return Connection(socket, deviceId, Fingerprint.sha256(der), der, peerClass)
    }

    private class Connection(
        private val socket: Socket,
        override val peerDeviceId: String,
        override val peerFingerprint: ByteArray,
        override val peerCertificate: ByteArray,
        override val peerClass: PeerClass,
    ) : SignalingConnection {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val sendLock = Any()

        override fun receive(): SignalingMessage? {
            while (true) {
                val text = Framing.read(input) ?: return null
                when (val decoded = MessageCodec.decodeSignaling(text)) {
                    is Decoded.Message -> return decoded.message
                    is Decoded.Unknown -> continue
                    Decoded.Malformed -> throw FrameException("malformed message")
                }
            }
        }

        override fun send(message: SignalingMessage) {
            val text = MessageCodec.encode(message)
            synchronized(sendLock) { Framing.write(output, text) }
        }

        override fun setReadTimeout(millis: Int) {
            socket.soTimeout = millis
        }

        override fun close() = socket.close()
    }

    companion object {
        private val PROTOCOLS = arrayOf("TLSv1.3")
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val SETUP_READ_TIMEOUT_MS = 30_000
    }
}
