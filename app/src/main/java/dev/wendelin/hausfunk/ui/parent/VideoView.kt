package dev.wendelin.hausfunk.ui.parent

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.wendelin.hausfunk.media.ParentSession
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** The baby's video, letterboxed, never cropped or stretched. */
@Composable
fun VideoView(session: ParentSession, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(session.eglBase.eglBaseContext, null)
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
