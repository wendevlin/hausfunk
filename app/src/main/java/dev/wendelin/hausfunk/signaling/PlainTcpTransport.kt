package dev.wendelin.hausfunk.signaling

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TEMPORARY, phase 2 only: unauthenticated, unencrypted signaling so the media path can be built
 * and tested first. Anyone on the Wi-Fi can connect. Phase 3 deletes this class; there is no flag
 * to keep it.
 */
class PlainTcpTransport : SignalingTransport {
    override fun listen(): SignalingServer = Server(ServerSocket(0))

    override fun connect(address: InetAddress, port: Int): SignalingConnection {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            return Connection(socket).apply { setReadTimeout(SETUP_READ_TIMEOUT_MS) }
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    private class Server(private val serverSocket: ServerSocket) : SignalingServer {
        override val port: Int get() = serverSocket.localPort

        override fun accept(): SignalingConnection {
            val socket = serverSocket.accept()
            socket.tcpNoDelay = true
            return Connection(socket).apply { setReadTimeout(SETUP_READ_TIMEOUT_MS) }
        }

        override fun close() = serverSocket.close()
    }

    private class Connection(private val socket: Socket) : SignalingConnection {
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
        const val CONNECT_TIMEOUT_MS = 10_000
        const val SETUP_READ_TIMEOUT_MS = 30_000
    }
}
