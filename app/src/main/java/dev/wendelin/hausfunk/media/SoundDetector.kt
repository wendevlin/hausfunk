package dev.wendelin.hausfunk.media

/**
 * Parent side: turns the baby's level messages into sound events and the noise gate. A sound
 * event is a level at or above the threshold for at least [MIN_SOUND_MS]. With the gate on,
 * playback is open only during a sound event and for the hold time after it. Pure logic; times
 * are elapsed milliseconds.
 */
class SoundDetector {
    var thresholdDbfs = DEFAULT_THRESHOLD_DBFS
    var gateEnabled = false
    var holdMs = DEFAULT_HOLD_MS

    /** When the last sound event was heard (its latest moment); null if none yet. */
    var lastSoundAt: Long? = null
        private set

    private var aboveSince: Long? = null

    /** A sound event is going on right now. */
    var sounding = false
        private set
    private var openUntil = Long.MIN_VALUE

    /** Feeds one level message received at [now]. */
    fun onLevel(dbfs: Float, now: Long) {
        if (dbfs >= thresholdDbfs) {
            val since = aboveSince ?: now.also { aboveSince = it }
            // Each message covers the interval before it, so a single message already lasted one.
            if (sounding || now - since + LEVEL_INTERVAL_MS >= MIN_SOUND_MS) {
                sounding = true
                lastSoundAt = now
            }
        } else {
            aboveSince = null
            if (sounding) {
                sounding = false
                openUntil = now + holdMs
            }
        }
    }

    /** Whether sound should be played at [now]. Always true with the gate off. */
    fun gateOpen(now: Long): Boolean = !gateEnabled || sounding || now < openUntil

    fun reset() {
        lastSoundAt = null
        aboveSince = null
        sounding = false
        openUntil = Long.MIN_VALUE
    }

    companion object {
        const val LEVEL_INTERVAL_MS = 200L
        const val MIN_SOUND_MS = 300L
        const val DEFAULT_THRESHOLD_DBFS = -40f
        const val DEFAULT_HOLD_MS = 10_000L
    }
}
