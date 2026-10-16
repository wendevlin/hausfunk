package dev.wendelin.hausfunk.ui.common

import androidx.compose.runtime.Immutable
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Zoom on a video: [scale] from 1 (whole picture) to [MAX], and the centre of the visible part
 * in display coordinates (0..1, as the picture is shown upright).
 */
@Immutable
data class Zoom(val scale: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f) {
    val zoomed: Boolean get() = scale > 1.01f

    /** Keeps the visible part inside the picture. */
    fun clamped(): Zoom {
        val s = scale.coerceIn(1f, MAX)
        val half = 0.5f / s
        return Zoom(s, centerX.coerceIn(half, 1f - half), centerY.coerceIn(half, 1f - half))
    }

    companion object {
        val NONE = Zoom()
        const val MAX = 4f
    }
}

/**
 * Crops every frame to the zoomed part before it reaches [target], scaled back to the frame's
 * size. Done on the frames rather than the view, so it works with a SurfaceView and the zoom
 * stays in picture-in-picture. Runs on the decoder thread.
 */
class ZoomSink : VideoSink {
    @Volatile var target: VideoSink? = null

    @Volatile var zoom: Zoom = Zoom.NONE

    override fun onFrame(frame: VideoFrame) {
        val out = target ?: return
        val z = zoom
        if (!z.zoomed) return out.onFrame(frame)
        val buffer = frame.buffer
        val w = buffer.width
        val h = buffer.height
        // The zoom is given as shown; the buffer may be stored rotated.
        val (bx, by) = when (frame.rotation) {
            90 -> z.centerY to 1f - z.centerX
            180 -> 1f - z.centerX to 1f - z.centerY
            270 -> 1f - z.centerY to z.centerX
            else -> z.centerX to z.centerY
        }
        val cw = ((w / z.scale).toInt() and 1.inv()).coerceIn(2, w)
        val ch = ((h / z.scale).toInt() and 1.inv()).coerceIn(2, h)
        val x = ((bx * w - cw / 2f).toInt().coerceIn(0, w - cw)) and 1.inv()
        val y = ((by * h - ch / 2f).toInt().coerceIn(0, h - ch)) and 1.inv()
        val cropped = VideoFrame(buffer.cropAndScale(x, y, cw, ch, w, h), frame.rotation, frame.timestampNs)
        out.onFrame(cropped)
        cropped.release()
    }
}
