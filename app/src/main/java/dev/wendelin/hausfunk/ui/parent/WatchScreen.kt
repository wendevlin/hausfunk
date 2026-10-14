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
import androidx.compose.runtime.Composable
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
 * Phase 2: video with sound, a close button and the connection state. Sound level, last sound,
 * battery and PiP arrive in later phases.
 */
@Composable
fun WatchScreen(session: ParentSession, state: ParentState, onRetry: () -> Unit, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(WatchColors.background),
    ) {
        if (state is ParentState.Connecting || state is ParentState.Connected) {
            VideoView(session, Modifier.fillMaxSize())
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .align(Alignment.TopStart),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.watch_stop), tint = WatchColors.onBackground)
        }
        val message = when (state) {
            is ParentState.Connecting -> stringResource(R.string.watch_connecting, state.baby.name)
            is ParentState.Busy -> stringResource(R.string.watch_busy, state.baby.name)
            is ParentState.Lost -> stringResource(R.string.watch_lost, state.baby.name)
            is ParentState.Connected, ParentState.Idle -> null
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
