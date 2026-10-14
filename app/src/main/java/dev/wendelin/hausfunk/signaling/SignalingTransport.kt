package dev.wendelin.hausfunk.signaling

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress

/**
 * How signaling connections are made. Phase 2 uses [PlainTcpTransport]; phase 3 replaces it with
 * mutual TLS and deletes the plain one. Callers never know which one they have.
 */
interface SignalingTransport {
    /** Baby side: opens a listening socket on a free port. */
    fun listen(): SignalingServer

    /** Parent side: connects to a baby. Blocking. */
    fun connect(address: InetAddress, port: Int): SignalingConnection
}

interface SignalingServer : Closeable {
    val port: Int

    /** Blocks until a parent connects. Throws [IOException] once closed. */
    fun accept(): SignalingConnection
}

interface SignalingConnection : Closeable {
    /**
     * Blocks for the next known message; unknown types are skipped. Returns null when the peer
     * closed the connection cleanly. Throws [IOException] on a malformed or oversize frame, a
     * timeout or a broken connection; the connection is unusable afterwards.
     */
    fun receive(): SignalingMessage?

    /** Thread-safe. Throws [IOException] if the connection is broken. */
    fun send(message: SignalingMessage)

    /** Read timeout while the session is being set up. Afterwards the socket may stay idle. */
    fun setReadTimeout(millis: Int)
}
