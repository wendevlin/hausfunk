package dev.wendelin.hausfunk.ui

import dev.wendelin.hausfunk.ui.common.Region
import dev.wendelin.hausfunk.ui.common.Zoom
import dev.wendelin.hausfunk.ui.common.visibleRegion
import dev.wendelin.hausfunk.ui.common.zoomBy
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomRegionTest {
    // A landscape picture on a portrait phone: bars above and below at zoom 1.
    private val vw = 1920f
    private val vh = 1080f
    private val bw = 1080f
    private val bh = 2000f

    private fun near(a: Float, b: Float) = assertTrue("$a != $b", abs(a - b) < 0.001f)

    @Test
    fun noZoomShowsEverything() {
        assertEquals(Region.ALL, visibleRegion(Zoom.NONE, vw, vh, bw, bh))
    }

    @Test
    fun zoomingFirstUsesTheBars() {
        // Twice the fitted size: half the width, still the full height (less bar).
        val r = visibleRegion(Zoom(2f), vw, vh, bw, bh)
        near(r.width, 0.5f)
        near(r.height, 1f)
        near(r.left, 0.25f)
    }

    @Test
    fun enoughZoomFillsTheScreen() {
        val r = visibleRegion(Zoom(4f), vw, vh, bw, bh)
        assertTrue(r.height < 1f)
        // Shown aspect equals the screen's.
        near(r.width * vw / (r.height * vh), bw / bh)
    }

    @Test
    fun regionStaysInsideThePicture() {
        val r = visibleRegion(Zoom(4f, centerX = 0f, centerY = 1f), vw, vh, bw, bh)
        near(r.left, 0f)
        near(r.top + r.height, 1f)
    }

    @Test
    fun pinchKeepsThePointUnderTheFingers() {
        val start = Zoom(2f)
        // Fingers at the view centre: the centre stays.
        val z = zoomBy(start, 1.5f, bw / 2, bh / 2, bw / 2, bh / 2, vw, vh, bw, bh)
        near(z.scale, 3f)
        near(z.centerX, 0.5f)
    }

    @Test
    fun zoomingOutFullyResets() {
        assertEquals(Zoom.NONE, zoomBy(Zoom(1.2f), 0.5f, 10f, 10f, 10f, 10f, vw, vh, bw, bh))
    }
}
