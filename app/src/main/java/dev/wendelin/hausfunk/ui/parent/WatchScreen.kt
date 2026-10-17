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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Tune
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import dev.wendelin.hausfunk.data.SoundPrefs
import dev.wendelin.hausfunk.media.AlarmState
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
import dev.wendelin.hausfunk.ui.common.Zoom
import dev.wendelin.hausfunk.ui.common.rememberHalfOpenFoldTop
import androidx.compose.foundation.layout.height
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
 * Video with sound, the sound level with the last sound, the baby's battery, the connection state
 * and the connection-loss alarm. In picture-in-picture ([inPip]) only the video with the last
 * sound and a dot while the connection is down; the same video view stays, so entering and
 * leaving PiP does not restart the renderer.
 */
@Composable
fun WatchScreen(
    session: ParentSession,
    state: ParentState,
    inPip: Boolean,
    soundPrefs: SoundPrefs,
    onSoundPrefs: ((SoundPrefs) -> SoundPrefs) -> Unit,
    zoom: Zoom,
    onZoom: (Zoom) -> Unit,
    onClose: () -> Unit,
    onAspectRatio: (Float) -> Unit,
    onVideoBounds: (Rect) -> Unit,
) {
    if (!inPip) {
        FullScreenInLandscape()
        KeepScreenOnWithLightIcons()
        BackHandler(onBack = onClose)
    }
    val context = LocalContext.current
    val debugBuild = remember { (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    val alarm by session.alarm.collectAsState()
    val level by session.level.collectAsState()
    val lastSound by session.lastSound.collectAsState()
    val status by session.status.collectAsState()
    val gateOpen by session.gateOpen.collectAsState()
    val history by session.soundHistory.collectAsState()
    var soundSettings by rememberSaveable { mutableStateOf(false) }
    ShowOverLockScreen(alarm == AlarmState.RINGING)
    // Galaxy Flip standing half open: the video above the fold, sound and status below it.
    val foldTop = if (inPip) null else rememberHalfOpenFoldTop()
    val upperArea = if (foldTop != null) Alignment.TopCenter else Alignment.Center
    val upperPadding = Modifier.padding(top = (foldTop ?: 0.dp) / 4)
    Box(
        Modifier
            .fillMaxSize()
            .background(WatchColors.background),
    ) {
        // The session knows whether frames arrive on the current connection, also after a reconnect.
        val videoArriving by session.videoArriving.collectAsState()
        var noFrameYet by remember { mutableStateOf(false) } // connected, but no frame for a while
        val connected = state is ParentState.Connected
        LaunchedEffect(connected, videoArriving) {
            noFrameYet = false
            if (connected && !videoArriving) {
                delay(NO_FRAME_HINT_MS)
                noFrameYet = true
            }
        }
        if (state is ParentState.WithBaby) {
            VideoView(
                session.eglBase,
                session::attach,
                session::detach,
                if (foldTop != null) Modifier.fillMaxWidth().height(foldTop) else Modifier.fillMaxSize(),
                onAspectRatio = onAspectRatio,
                onBounds = onVideoBounds,
                zoom = zoom,
                onZoom = if (inPip) null else onZoom,
            )
        }
        if (inPip) {
            PipBadge(state, lastSound, Modifier.align(Alignment.BottomStart))
            return@Box
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
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp)
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
        IconButton(
            onClick = { soundSettings = true },
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .align(Alignment.TopEnd),
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.watch_sound_settings), tint = WatchColors.onBackground)
        }
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .align(Alignment.BottomCenter)
                .widthIn(max = 640.dp)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VolumeWarning(AudioManager.STREAM_MUSIC)
            VolumeWarning(AudioManager.STREAM_ALARM)
            SoundStatus(
                level,
                soundPrefs.thresholdDbfs,
                lastSound,
                status,
                muted = !gateOpen,
                history = history,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        if (alarm != AlarmState.OFF && state is ParentState.WithBaby) {
            val noLan by session.noLan.collectAsState()
            AlarmCard(
                state.baby.name,
                noLan = noLan,
                ringing = alarm == AlarmState.RINGING,
                onAcknowledge = session::acknowledgeAlarm,
                modifier = Modifier.align(upperArea).then(upperPadding),
            )
        } else {
            val message = when (state) {
                is ParentState.Connecting -> stringResource(R.string.watch_connecting, state.baby.name)
                is ParentState.Busy -> stringResource(R.string.watch_busy, state.baby.name) + "\n" +
                    stringResource(R.string.watch_busy_retrying)
                is ParentState.Reconnecting -> stringResource(R.string.watch_reconnecting, state.baby.name)
                is ParentState.Connected -> if (noFrameYet) stringResource(R.string.watch_no_picture) else null
                ParentState.Idle -> null
            }
            if (message != null) {
                Column(
                    Modifier
                        .align(upperArea)
                        .then(upperPadding)
                        .padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(message, color = WatchColors.onBackground, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    if (state is ParentState.Busy || state is ParentState.Reconnecting) {
                        Button(onClick = session::retryNow, modifier = Modifier.padding(top = 16.dp)) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }
    }
    if (soundSettings && !inPip) {
        SoundSettingsSheet(level, soundPrefs, onSoundPrefs, onDismiss = { soundSettings = false })
    }
}

/** Picture-in-picture: the last sound, and a red dot while the connection is down. */
@Composable
private fun PipBadge(state: ParentState, lastSound: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state !is ParentState.Connected) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
        Text(lastSoundText(lastSound), color = WatchColors.onBackground, style = MaterialTheme.typography.labelSmall)
    }
}

/** The connection-loss alarm on screen: what happened and "Acknowledge" while it rings. */
@Composable
private fun AlarmCard(
    babyName: String,
    noLan: Boolean,
    ringing: Boolean,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(24.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (noLan) R.string.alarm_title_no_lan else R.string.alarm_title, babyName),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            if (noLan) stringResource(R.string.alarm_body_no_lan, babyName) else stringResource(R.string.alarm_body),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (ringing) {
            Button(onClick = onAcknowledge, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.alarm_acknowledge))
            }
        }
    }
}

/** While the alarm rings, the watch screen shows over the lock screen and turns it on. */
@Composable
private fun ShowOverLockScreen(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(enabled, activity) {
        activity.setShowWhenLocked(enabled)
        activity.setTurnScreenOn(enabled)
        onDispose {
            activity.setShowWhenLocked(false)
            activity.setTurnScreenOn(false)
        }
    }
}

/**
 * While watching, the screen stays on (it is the monitor), and the status bar uses light icons
 * on the always-dark background.
 */
@Composable
private fun KeepScreenOnWithLightIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, view)
        val lightBars = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        view.keepScreenOn = true
        onDispose {
            controller.isAppearanceLightStatusBars = lightBars
            view.keepScreenOn = false
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
