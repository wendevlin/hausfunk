package dev.wendelin.hausfunk.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun doublesThenStaysAtTenSeconds() {
        val b = Backoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), List(6) { b.next() })
        b.reset()
        assertEquals(1_000L, b.next())
    }
}

class LevelMeterTest {
    private fun pcm(frames: Int, channels: Int, amplitude: Double): ByteArray {
        val out = ByteArray(frames * channels * 2)
        for (f in 0 until frames) {
            val v = (amplitude * 32767 * sin(2 * PI * 440 * f / 48_000)).roundToInt()
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                out[i] = v.toByte()
                out[i + 1] = (v shr 8).toByte()
            }
        }
        return out
    }

    @Test
    fun silenceIsTheFloor() {
        val m = LevelMeter(48_000)
        m.add(ByteArray(4_800 * 2), channels = 1)
        assertEquals(LevelMeter.FLOOR_DBFS, m.take())
        assertNull(m.take())
    }

    @Test
    fun fullScaleSineIsAboutMinusThree() {
        val m = LevelMeter(48_000)
        m.add(pcm(4_800, 1, 1.0), channels = 1)
        assertTrue(abs(m.take()!! + 3.01f) < 0.1f)
    }

    @Test
    fun stereoCountsLikeMonoAndPartialWindowsWait() {
        val m = LevelMeter(48_000)
        m.add(pcm(2_400, 2, 0.1), channels = 2)
        assertNull(m.take()) // half a window
        m.add(pcm(2_400, 2, 0.1), channels = 2)
        assertTrue(abs(m.take()!! + 23.01f) < 0.1f)
    }

    @Test
    fun takeReturnsTheLoudestWindow() {
        val m = LevelMeter(48_000)
        m.add(pcm(4_800, 1, 0.5), channels = 1)
        m.add(pcm(4_800, 1, 0.01), channels = 1)
        assertTrue(abs(m.take()!! + 9.03f) < 0.1f)
    }
}

class SoundDetectorTest {
    private val quiet = -60f
    private val loud = -20f

    @Test
    fun singleBlipIsNoSound() {
        val d = SoundDetector()
        d.onLevel(loud, 1_000)
        d.onLevel(quiet, 1_200)
        assertNull(d.lastSoundAt)
    }

    @Test
    fun twoLoudMessagesAreASound() {
        val d = SoundDetector()
        d.onLevel(loud, 1_000)
        d.onLevel(loud, 1_200)
        assertEquals(1_200L, d.lastSoundAt)
        d.onLevel(loud, 1_400)
        assertEquals(1_400L, d.lastSoundAt)
        d.onLevel(quiet, 1_600)
        assertEquals(1_400L, d.lastSoundAt)
    }

    @Test
    fun gateIsAlwaysOpenWhenOff() {
        val d = SoundDetector()
        d.onLevel(quiet, 1_000)
        assertTrue(d.gateOpen(1_000))
    }

    @Test
    fun gateOpensOnSoundAndHolds() {
        val d = SoundDetector().apply {
            gateEnabled = true
            holdMs = 10_000
        }
        d.onLevel(quiet, 1_000)
        assertFalse(d.gateOpen(1_000))
        d.onLevel(loud, 1_200)
        assertFalse(d.gateOpen(1_200)) // still deciding
        d.onLevel(loud, 1_400)
        assertTrue(d.gateOpen(1_400))
        d.onLevel(quiet, 1_600)
        assertTrue(d.gateOpen(11_599))
        assertFalse(d.gateOpen(11_600))
    }

    @Test
    fun thresholdIsInclusive() {
        val d = SoundDetector().apply { thresholdDbfs = -30f }
        d.onLevel(-30f, 0)
        d.onLevel(-30f, 200)
        assertEquals(200L, d.lastSoundAt)
    }
}
