package dev.wendelin.hausfunk.ui.common

import kotlin.math.min

/**
 * Zoom on a video: [scale] is relative to the whole picture fitted into the view (1 = all of it),
 * the centre is in the picture's display coordinates (0..1, as shown upright). Pure logic.
 */
data class Zoom(val scale: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f) {
    val zoomed: Boolean get() = scale > 1.01f

    companion object {
        val NONE = Zoom()
        const val MAX = 6f
    }
}

/** A part of the picture in display coordinates (0..1). */
data class Region(val left: Float, val top: Float, val width: Float, val height: Float) {
    val centerX: Float get() = left + width / 2
    val centerY: Float get() = top + height / 2

    companion object {
        val ALL = Region(0f, 0f, 1f, 1f)
    }
}

/**
 * The part of a [videoW]×[videoH] picture (display pixels) that a [boxW]×[boxH] view shows at
 * [zoom]. Zooming in first fills the bars beside or above the picture, then crops it; the region
 * is kept inside the picture.
 */
fun visibleRegion(zoom: Zoom, videoW: Float, videoH: Float, boxW: Float, boxH: Float): Region {
    if (videoW <= 0f || videoH <= 0f || boxW <= 0f || boxH <= 0f || !zoom.zoomed) return Region.ALL
    val k = zoom.scale.coerceIn(1f, Zoom.MAX) * min(boxW / videoW, boxH / videoH)
    val w = min(1f, boxW / (k * videoW))
    val h = min(1f, boxH / (k * videoH))
    val cx = zoom.centerX.coerceIn(w / 2, 1f - w / 2)
    val cy = zoom.centerY.coerceIn(h / 2, 1f - h / 2)
    return Region(cx - w / 2, cy - h / 2, w, h)
}

/**
 * A pinch or drag of the fingers' centre from [prevX],[prevY] to [curX],[curY] (view pixels),
 * with the fingers' spread changed by [factor]: the point of the picture under the fingers stays
 * under them.
 */
fun zoomBy(
    zoom: Zoom,
    factor: Float,
    prevX: Float,
    prevY: Float,
    curX: Float,
    curY: Float,
    videoW: Float,
    videoH: Float,
    boxW: Float,
    boxH: Float,
): Zoom {
    if (videoW <= 0f || videoH <= 0f || boxW <= 0f || boxH <= 0f) return zoom
    val fit = min(boxW / videoW, boxH / videoH)
    val start = visibleRegion(zoom, videoW, videoH, boxW, boxH)
    val oldScale = zoom.scale.coerceIn(1f, Zoom.MAX)
    val newScale = (oldScale * factor).coerceIn(1f, Zoom.MAX)
    // Picture pixels under the fingers before; the view centre shows the region centre.
    val px = start.centerX * videoW + (prevX - boxW / 2) / (oldScale * fit)
    val py = start.centerY * videoH + (prevY - boxH / 2) / (oldScale * fit)
    val cx = (px - (curX - boxW / 2) / (newScale * fit)) / videoW
    val cy = (py - (curY - boxH / 2) / (newScale * fit)) / videoH
    val region = visibleRegion(Zoom(newScale, cx, cy), videoW, videoH, boxW, boxH)
    return if (newScale <= 1.01f) Zoom.NONE else Zoom(newScale, region.centerX, region.centerY)
}
