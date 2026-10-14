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
        // Android accepts ratios between 1:2.39 and 2.39:1.
        val ratio = aspectRatio.coerceIn(MIN_RATIO, MAX_RATIO)
        activity.setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAutoEnterEnabled(enabled)
                .setSeamlessResizeEnabled(true)
                .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
                // Where the video is now, so the window animates from it into the PiP window.
                .apply { if (sourceRect != null && !sourceRect.isEmpty) setSourceRectHint(sourceRect) }
                .build(),
        )
        onDispose {
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }
}

private const val MIN_RATIO = 0.42f
private const val MAX_RATIO = 2.38f
