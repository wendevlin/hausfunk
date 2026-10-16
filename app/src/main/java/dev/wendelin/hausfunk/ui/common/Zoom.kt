package dev.wendelin.hausfunk.ui.common

import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Crops every frame to [region] before it reaches [target]. Done on the frames rather than the
 * view, so it works with a SurfaceView and picture-in-picture shows the same part. Reports the
 * uncropped picture size (as displayed) through [onVideoSize]. Runs on the decoder thread.
 */
class ZoomSink(private val onVideoSize: (width: Int, height: Int) -> Unit) : VideoSink {
    @Volatile var target: VideoSink? = null

    @Volatile var region: Region = Region.ALL
    private var lastW = 0
    private var lastH = 0

    override fun onFrame(frame: VideoFrame) {
        val out = target ?: return
        val buffer = frame.buffer
        val w = buffer.width
        val h = buffer.height
        val sideways = frame.rotation % 180 != 0
        val (dw, dh) = if (sideways) h to w else w to h
        if (dw != lastW || dh != lastH) {
            lastW = dw
            lastH = dh
            onVideoSize(dw, dh)
        }
        val r = region
        if (r == Region.ALL) return out.onFrame(frame)
        // The region is given as shown; the buffer may be stored rotated.
        val b = when (frame.rotation) {
            90 -> Region(r.top, 1f - r.left - r.width, r.height, r.width)
            180 -> Region(1f - r.left - r.width, 1f - r.top - r.height, r.width, r.height)
            270 -> Region(1f - r.top - r.height, r.left, r.height, r.width)
            else -> r
        }
        val cw = ((b.width * w).toInt() and 1.inv()).coerceIn(2, w)
        val ch = ((b.height * h).toInt() and 1.inv()).coerceIn(2, h)
        val x = ((b.left * w).toInt() and 1.inv()).coerceIn(0, w - cw)
        val y = ((b.top * h).toInt() and 1.inv()).coerceIn(0, h - ch)
        val cropped = VideoFrame(buffer.cropAndScale(x, y, cw, ch, cw, ch), frame.rotation, frame.timestampNs)
        out.onFrame(cropped)
        cropped.release()
    }
}
