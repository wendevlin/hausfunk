package dev.wendelin.hausfunk.media

/** Delays between reconnect attempts: 1, 2, 4 and 8 s, then every 10 s. */
class Backoff {
    private var attempt = 0

    fun next(): Long = (DELAYS_MS.getOrNull(attempt) ?: STEADY_MS).also { attempt++ }

    fun reset() {
        attempt = 0
    }

    private companion object {
        val DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)
        const val STEADY_MS = 10_000L
    }
}
