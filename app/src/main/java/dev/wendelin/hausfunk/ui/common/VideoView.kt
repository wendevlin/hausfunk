package dev.wendelin.hausfunk.ui.common

import android.graphics.Rect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

/**
 * A WebRTC video, letterboxed, never stretched. SurfaceViewRenderer crops frames to its own aspect
 * ratio, so the view itself is sized to the shown part's aspect ratio and centred. Zooming in
 * first uses the bars beside the picture, then crops it. [attach] and [detach] connect the
 * renderer to the session that owns the video track.
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
    // The whole picture as displayed; 0 until the first frame arrives. Small changes (WebRTC
    // scaling 1280x720 to 852x480) are ignored: every new size re-lays out the surface.
    var video by remember { mutableStateOf(IntSize.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val zoomSink = remember {
        ZoomSink { w, h ->
            val old = video
            val ratio = w.toFloat() / h
            val oldRatio = if (old.height > 0) old.width.toFloat() / old.height else 0f
            if (abs(ratio - oldRatio) > ASPECT_TOLERANCE * ratio) video = IntSize(w, h) // any thread
        }
    }
    val region = visibleRegion(zoom, video.width.toFloat(), video.height.toFloat(), box.width.toFloat(), box.height.toFloat())
    SideEffect { zoomSink.region = region }
    val aspectRatio = if (video.height > 0) region.width * video.width / (region.height * video.height) else 0f
    val aspectChanged by rememberUpdatedState(onAspectRatio)
    LaunchedEffect(aspectRatio) { if (aspectRatio > 0f) aspectChanged(aspectRatio) }
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val currentZoom by rememberUpdatedState(zoom)

    val gestures = if (onZoom == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, factor, _ ->
                    val before = centroid - pan
                    onZoom(
                        zoomBy(
                            currentZoom, factor, before.x, before.y, centroid.x, centroid.y,
                            video.width.toFloat(), video.height.toFloat(), size.width.toFloat(), size.height.toFloat(),
                        ),
                    )
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onZoom(Zoom.NONE) }) }
    }
    Box(
        modifier
            .onSizeChanged { box = it }
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = (if (aspectRatio > 0f) Modifier.aspectRatio(aspectRatio) else Modifier.fillMaxSize())
                .onGloballyPositioned { coordinates ->
                    val b = coordinates.boundsInWindow()
                    onBounds(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
                },
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(
                        eglBase.eglBaseContext,
                        object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() = firstFrame()
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
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

/** Relative aspect-ratio change below which the view keeps its size. */
private const val ASPECT_TOLERANCE = 0.02f
