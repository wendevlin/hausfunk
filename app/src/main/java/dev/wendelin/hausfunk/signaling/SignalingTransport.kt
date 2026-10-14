package dev.wendelin.hausfunk.signaling

import dev.wendelin.hausfunk.identity.PeerClass
import java.io.Closeable
import java.io.IOException

interface SignalingServer : Closeable {
    val port: Int

    /** Blocks until a TCP connection arrives; the TLS handshake happens in [Pending.handshake]. */
    fun accept(): Pending

    /** An accepted socket before its handshake, so a slow peer never blocks [accept]. */
    interface Pending : Closeable {
        /** Runs the TLS handshake (10 s limit) and classifies the peer. Throws [IOException] if rejected. */
        fun handshake(): SignalingConnection
    }
}

/** One authenticated signaling connection: the peer is whoever holds the certificate it presented. */
interface SignalingConnection : Closeable {
    /** Device ID (certificate fingerprint, hex) of the peer, from the TLS session. */
    val peerDeviceId: String

    /** SHA-256 of the peer certificate, from the TLS session. */
    val peerFingerprint: ByteArray

    /** DER of the peer certificate, for the trust store. */
    val peerCertificate: ByteArray

    /** TRUSTED for paired peers; PAIRING only for a pairing connection. */
    val peerClass: PeerClass

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
