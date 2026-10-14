package dev.wendelin.hausfunk.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import android.graphics.Rect
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wendelin.hausfunk.ui.common.VideoView
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.media.ParentSession
import dev.wendelin.hausfunk.media.ParentState

/** The watch screen is always dark, whatever the system theme, so it never lights up a bedroom. */
object WatchColors {
    val background = Color.Black
    val onBackground = Color.White.copy(alpha = 0.8f)
}

/**
 * Video with sound, a close button and the connection state. Sound level, last sound and battery
 * arrive in phase 5.
 */
@Composable
fun WatchScreen(
    session: ParentSession,
    state: ParentState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onAspectRatio: (Float) -> Unit = {},
    onVideoBounds: (Rect) -> Unit = {},
) {
    FullScreenInLandscape()
    val context = LocalContext.current
    val debugBuild = remember { (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    Box(
        Modifier
            .fillMaxSize()
            .background(WatchColors.background),
    ) {
        // Whether a picture arrived on this connection; reset for every new connection.
        var gotFrame by remember { mutableStateOf(false) }
        var noFrameYet by remember { mutableStateOf(false) }
        LaunchedEffect(state is ParentState.Connecting) { if (state is ParentState.Connecting) gotFrame = false }
        LaunchedEffect(state is ParentState.Connected, gotFrame) {
            noFrameYet = false
            if (state is ParentState.Connected && !gotFrame) {
                delay(NO_FRAME_HINT_MS)
                noFrameYet = true
            }
        }
        if (state is ParentState.Connecting || state is ParentState.Connected) {
            VideoView(
                session.eglBase,
                session::attach,
                session::detach,
                Modifier.fillMaxSize(),
                onFirstFrame = { gotFrame = true },
                onAspectRatio = onAspectRatio,
                onBounds = onVideoBounds,
            )
        }
        if (debugBuild) {
            val stats by session.videoStats.collectAsState()
            stats?.let { s ->
                // Debug builds only: what actually arrives, to judge quality on a real network.
                Text(
                    "${s.width}×${s.height} · ${"%.0f".format(s.fps)} fps · ${s.kbps} kbit/s · ${s.codec}",
                    color = WatchColors.onBackground,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .align(Alignment.TopStart),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.watch_stop), tint = WatchColors.onBackground)
        }
        val loudness = rememberLoudness()
        VolumeWarning(
            loudness,
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
        val message = when (state) {
            is ParentState.Connecting -> stringResource(R.string.watch_connecting, state.baby.name)
            is ParentState.Busy -> stringResource(R.string.watch_busy, state.baby.name)
            is ParentState.Lost -> stringResource(R.string.watch_lost, state.baby.name)
            is ParentState.Connected -> if (noFrameYet && !gotFrame) stringResource(R.string.watch_no_picture) else null
            ParentState.Idle -> null
        }
        if (message != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = WatchColors.onBackground, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                if (state is ParentState.Busy || state is ParentState.Lost) {
                    Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }
}

/** In landscape the video gets the whole screen: system bars hide until swiped in. */
@Composable
private fun FullScreenInLandscape() {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(landscape, window) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (landscape) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** After this long without a picture, the watch screen says so (sound may still be fine). */
private const val NO_FRAME_HINT_MS = 5_000L
