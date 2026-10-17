package dev.wendelin.hausfunk.media

/** The connection-loss alarm. */
enum class AlarmState {
    OFF,
    RINGING,

    /** The parent silenced it; the link is still down. */
    ACKNOWLEDGED,
}

/**
 * When the connection-loss alarm rings: once a watch has had a working link (or resumes one after
 * a restart), a loss that lasts [graceMs] rings it, and only a working link again ends it. Pure
 * logic: the caller reports the link with the current time (elapsed ms) and calls [check] when
 * [dueIn] says so.
 */
class LossAlarm {
    var graceMs = DEFAULT_GRACE_MS

    var state = AlarmState.OFF
        private set

    /** A loss counts: the link worked before in this watch, or the watch is being resumed. */
    private var armed = false
    private var lostSince: Long? = null

    /** A new watch. [resume]: it was going on before the service was restarted. */
    fun reset(resume: Boolean) {
        armed = resume
        lostSince = null
        state = AlarmState.OFF
    }

    /** The baby answers end to end. */
    fun onUp() {
        armed = true
        lostSince = null
        state = AlarmState.OFF
    }

    /** The link is down at [now] (repeated calls keep the first loss time). */
    fun onDown(now: Long) {
        if (armed && lostSince == null) lostSince = now
    }

    /** Milliseconds from [now] until [check] may ring the alarm; null if nothing is pending. */
    fun dueIn(now: Long): Long? {
        val since = lostSince ?: return null
        if (state != AlarmState.OFF) return null
        return (since + graceMs - now).coerceAtLeast(0)
    }

    fun check(now: Long) {
        val since = lostSince ?: return
        if (state == AlarmState.OFF && now - since >= graceMs) state = AlarmState.RINGING
    }

    fun acknowledge() {
        if (state == AlarmState.RINGING) state = AlarmState.ACKNOWLEDGED
    }

    /** Watching ends: nothing can ring. */
    fun stop() {
        armed = false
        lostSince = null
        state = AlarmState.OFF
    }

    companion object {
        const val DEFAULT_GRACE_MS = 15_000L
    }
}
