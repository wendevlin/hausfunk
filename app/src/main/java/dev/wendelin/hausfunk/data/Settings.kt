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
    /** Parent: how long a lost connection may stay lost before the alarm sounds. */
    val alarmGraceSeconds: Int = DEFAULT_ALARM_GRACE_SECONDS,
    /** Parent: noise threshold and gate per baby device ID. */
    val sound: Map<String, SoundPrefs> = emptyMap(),
) {
    fun soundFor(deviceId: String): SoundPrefs = sound[deviceId] ?: SoundPrefs()

    companion object {
        const val DEFAULT_ALARM_GRACE_SECONDS = 15
        val ALARM_GRACE_RANGE = 5..60
    }
}

/** How one parent listens to one baby device. */
@Serializable
data class SoundPrefs(
    /** Sound at or above this level (dBFS) counts as a sound event. */
    val thresholdDbfs: Float = -40f,
    /** Play only sound events (plus the hold time), mute everything quieter. */
    val gate: Boolean = false,
    val holdSeconds: Int = 10,
) {
    companion object {
        val THRESHOLD_RANGE = -70f..-10f
        val HOLD_RANGE = 3..30
    }
}
