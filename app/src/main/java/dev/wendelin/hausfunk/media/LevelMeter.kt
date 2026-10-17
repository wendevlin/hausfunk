package dev.wendelin.hausfunk.media

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Sound level of 16-bit PCM in dBFS: the RMS of every 100 ms window. [take] returns the loudest
 * window since the previous call, so a short cry between two messages is not missed. [add] and
 * [take] may run on different threads.
 */
class LevelMeter(sampleRate: Int) {
    private val windowFrames = (sampleRate / 10).coerceAtLeast(1)
    private var sumSquares = 0.0
    private var frames = 0
    private var loudest: Float? = null

    /** [pcm] is little-endian 16-bit, [channels] interleaved; all channels count alike. */
    @Synchronized
    fun add(pcm: ByteArray, channels: Int) {
        val step = 2 * channels.coerceAtLeast(1)
        var i = 0
        while (i + step <= pcm.size) {
            var c = 0
            while (c < step) {
                val sample = (pcm[i + c].toInt() and 0xff) or (pcm[i + c + 1].toInt() shl 8)
                sumSquares += sample.toDouble() * sample / channels
                c += 2
            }
            frames++
            if (frames == windowFrames) {
                val db = toDbfs(sqrt(sumSquares / frames))
                loudest = maxOf(loudest ?: db, db)
                sumSquares = 0.0
                frames = 0
            }
            i += step
        }
    }

    /** The loudest full window since the last call; null if none completed. */
    @Synchronized
    fun take(): Float? = loudest.also { loudest = null }

    companion object {
        /** Digital silence; also the bottom of every scale shown to the user. */
        const val FLOOR_DBFS = -90f

        fun toDbfs(rms: Double): Float {
            if (rms <= 0.0) return FLOOR_DBFS
            return (20 * log10(rms / 32768.0)).toFloat().coerceIn(FLOOR_DBFS, 0f)
        }
    }
}
