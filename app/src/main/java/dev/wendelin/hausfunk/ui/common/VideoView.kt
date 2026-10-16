package dev.wendelin.hausfunk.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import kotlin.math.abs

/**
 * A WebRTC video, letterboxed, never cropped or stretched. SurfaceViewRenderer crops frames to its
 * own aspect ratio, so the view itself is sized to the video's aspect ratio and centred.
 * [attach] and [detach] connect the renderer to the session that owns the video track.
 */
@Composable
fun VideoView(
    eglBase: EglBase,
    attach: (VideoSink) -> Unit,
    detach: (VideoSink) -> Unit,
    modifier: Modifier = Modifier,
    onFirstFrame: () -> Unit = {},
    /** Width / height of the video as displayed, whenever it changes noticeably. */
    onAspectRatio: (Float) -> Unit = {},
    /** Where the video is drawn, in window coordinates (for the picture-in-picture animation). */
    onBounds: (Rect) -> Unit = {},
    zoom: Zoom = Zoom.NONE,
    /** Set to allow pinch to zoom, drag to move and double tap to reset. */
    onZoom: ((Zoom) -> Unit)? = null,
) {
    val zoomSink = remember { ZoomSink() }
    SideEffect { zoomSink.zoom = zoom }
    val currentZoom by rememberUpdatedState(zoom)
    // Width / height of the frames as displayed; 0 until the first frame arrives.
    var aspectRatio by remember { mutableFloatStateOf(0f) }
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val aspectChanged by rememberUpdatedState(onAspectRatio)
    Box(modifier, contentAlignment = Alignment.Center) {
        val videoArea = (if (aspectRatio > 0f) Modifier.aspectRatio(aspectRatio) else Modifier.fillMaxSize())
            .onGloballyPositioned { coordinates ->
                val b = coordinates.boundsInWindow()
                onBounds(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
            }
        val gestures = if (onZoom == null) {
            Modifier
        } else {
            Modifier
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val z = currentZoom
                        val scale = (z.scale * gestureZoom).coerceIn(1f, Zoom.MAX)
                        // The point under the fingers stays under the fingers.
                        val before = centroid - pan
                        val px = z.centerX + (before.x / size.width - 0.5f) / z.scale
                        val py = z.centerY + (before.y / size.height - 0.5f) / z.scale
                        val cx = px - (centroid.x / size.width - 0.5f) / scale
                        val cy = py - (centroid.y / size.height - 0.5f) / scale
                        onZoom(Zoom(scale, cx, cy).clamped())
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onZoom(Zoom.NONE) }) }
        }
        Box(videoArea.then(gestures)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(
                            eglBase.eglBaseContext,
                            object : RendererCommon.RendererEvents {
                                override fun onFirstFrameRendered() = firstFrame()

                                // Called on the render thread; Compose state may be written from any thread.
                                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                                    if (width <= 0 || height <= 0) return
                                    val sideways = rotation % 180 != 0
                                    val ratio = if (sideways) height.toFloat() / width else width.toFloat() / height
                                    // Ignore rounding-level changes (e.g. 1280x720 to 852x480): every
                                    // new size re-lays out the surface, which shows as a flicker.
                                    if (abs(ratio - aspectRatio) > ASPECT_TOLERANCE * ratio) {
                                        aspectRatio = ratio
                                        aspectChanged(ratio)
                                    }
                                }
                            },
                        )
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        zoomSink.target = this
                        attach(zoomSink)
                    }
                },
                onRelease = { renderer ->
                    detach(zoomSink)
                    zoomSink.target = null
                    renderer.release()
                },
            )
        }
    }
}

/** Relative aspect-ratio change below which the view keeps its size. */
private const val ASPECT_TOLERANCE = 0.02f
