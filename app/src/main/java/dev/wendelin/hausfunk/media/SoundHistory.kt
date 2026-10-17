package dev.wendelin.hausfunk.media

/**
 * The last [BUCKETS] × [BUCKET_MS] (30 minutes) of sound level for the parent's history strip:
 * the loudest level of every 5 s and whether a sound event happened in it. In memory only, never
 * stored. Pure logic; times are elapsed milliseconds.
 */
class SoundHistory {
    /** [peak] is null where no level arrived (not connected). */
    data class Bucket(val peak: Float?, val sound: Boolean)

    private val buckets = ArrayDeque<Pair<Long, Bucket>>()

    fun add(dbfs: Float, sounding: Boolean, now: Long) {
        val index = now / BUCKET_MS
        val last = buckets.lastOrNull()
        if (last != null && last.first == index) {
            val b = last.second
            buckets[buckets.lastIndex] = index to Bucket(maxOf(b.peak ?: dbfs, dbfs), b.sound || sounding)
        } else {
            buckets.addLast(index to Bucket(dbfs, sounding))
        }
        while (buckets.isNotEmpty() && buckets.first().first <= index - BUCKETS) buckets.removeFirst()
    }

    /** Oldest first, exactly [BUCKETS] entries ending with the bucket of [now]. */
    fun snapshot(now: Long): List<Bucket> {
        val end = now / BUCKET_MS
        val byIndex = buckets.toMap()
        return (end - BUCKETS + 1..end).map { byIndex[it] ?: EMPTY }
    }

    fun clear() = buckets.clear()

    companion object {
        const val BUCKET_MS = 5_000L
        const val BUCKETS = 360
        private val EMPTY = Bucket(null, false)
    }
}
