package dev.wendelin.hausfunk.data

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val deviceName: String = "",
    val onboardingComplete: Boolean = false,
    val lastRole: Role? = null,
    /** Device ID of the identity the trust store belongs to; detects a lost Keystore key. */
    val identityDeviceId: String? = null,
    /** Set when the identity had to be regenerated; cleared once the user has seen the notice. */
    val identityResetNotice: Boolean = false,
)
