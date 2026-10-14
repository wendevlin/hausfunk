package dev.wendelin.hausfunk.ui.parent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.wendelin.hausfunk.media.ParentSession
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The baby's video, letterboxed, never cropped or stretched. SurfaceViewRenderer crops frames to
 * its own aspect ratio, so the view itself is sized to the video's aspect ratio and centred.
 */
@Composable
fun VideoView(session: ParentSession, modifier: Modifier = Modifier) {
    // Width / height of the frames as displayed; 0 until the first frame arrives.
    var aspectRatio by remember { mutableFloatStateOf(0f) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = if (aspectRatio > 0f) Modifier.aspectRatio(aspectRatio) else Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(
                        session.eglBase.eglBaseContext,
                        object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() = Unit

                            // Called on the render thread; Compose state may be written from any thread.
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                                if (width <= 0 || height <= 0) return
                                val sideways = rotation % 180 != 0
                                aspectRatio = if (sideways) height.toFloat() / width else width.toFloat() / height
                            }
                        },
                    )
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    session.attach(this)
                }
            },
            onRelease = { renderer ->
                session.detach(renderer)
                renderer.release()
            },
        )
    }
}
