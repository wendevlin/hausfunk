package io.github.wendevlin.hausfunk.data

import kotlinx.serialization.Serializable

@Serializable
data class TrustStore(val devices: List<TrustedDevice> = emptyList())

@Serializable
data class TrustedDevice(
    /** Lowercase hex SHA-256 fingerprint of [certificate]. */
    val deviceId: String,
    val name: String,
    /** DER-encoded X.509 certificate, base64. */
    val certificate: String,
    /** Role the peer had when it was paired. */
    val role: Role,
    /** Epoch milliseconds. */
    val pairedAt: Long,
)
