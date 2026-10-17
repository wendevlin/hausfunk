package dev.wendelin.hausfunk.ui.common

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

/** Whether the activity currently shows as a picture-in-picture window. */
@Composable
fun rememberInPictureInPicture(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/**
 * While [enabled], leaving the app (Home gesture) turns it into a picture-in-picture window with
 * the video's [aspectRatio]. Disabled again when this leaves the composition.
 */
@Composable
fun AutoPictureInPicture(enabled: Boolean, aspectRatio: Float, sourceRect: Rect?) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, enabled, aspectRatio, sourceRect) {
        // Android accepts ratios between 1:2.39 and 2.39:1; some phones are stricter.
        val ratio = if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio.coerceIn(MIN_RATIO, MAX_RATIO) else 16f / 9f
        setParams(activity) {
            setAutoEnterEnabled(enabled)
            setSeamlessResizeEnabled(true)
            setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            // Where the video is now, so the window animates from it into the PiP window.
            if (sourceRect != null && !sourceRect.isEmpty) setSourceRectHint(sourceRect)
        }
        onDispose { setParams(activity) { setAutoEnterEnabled(false) } }
    }
}

/** A refused picture-in-picture setting must never crash the app; without it there is just no PiP. */
private fun setParams(activity: android.app.Activity, build: PictureInPictureParams.Builder.() -> Unit) {
    try {
        activity.setPictureInPictureParams(PictureInPictureParams.Builder().apply(build).build())
    } catch (e: IllegalArgumentException) {
        android.util.Log.w("PictureInPicture", "parameters refused", e)
    } catch (e: IllegalStateException) {
        android.util.Log.w("PictureInPicture", "parameters refused", e)
    }
}

private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2.33f
