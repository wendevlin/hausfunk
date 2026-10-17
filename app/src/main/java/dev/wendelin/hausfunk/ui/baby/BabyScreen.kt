package dev.wendelin.hausfunk.ui.baby

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.service.BabyService
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import dev.wendelin.hausfunk.media.BabySession
import dev.wendelin.hausfunk.media.BabyState
import dev.wendelin.hausfunk.pairing.PairingState
import dev.wendelin.hausfunk.ui.common.PermissionHint
import dev.wendelin.hausfunk.ui.common.VideoView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.wendelin.hausfunk.ui.common.rememberMissingPermissions
import dev.wendelin.hausfunk.ui.pairing.PairingContent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The baby screen. Monitoring itself runs in [BabyService], started here once every permission is
 * granted (Android only lets camera and microphone services start from a visible screen). Back
 * leaves monitoring running; only "Stop" (here or in the notification) ends it. A parent asking
 * to pair takes over the screen once there are emojis to compare.
 */
@Composable
fun BabyRoute(app: HausfunkApplication, onStop: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val missing = rememberMissingPermissions(baby = true)
    val session by app.babySession.collectAsState()
    val state by remember(session) { session?.state ?: MutableStateFlow(BabyState()) }.collectAsState()
    val stop = {
        BabyService.stop(context)
        onStop()
    }
    LaunchedEffect(missing.isEmpty()) {
        if (missing.isNotEmpty()) return@LaunchedEffect
        if (app.babySession.value == null) BabyService.start(context)
        // Once running, a stop from elsewhere (the notification) leaves this screen too.
        app.babySession.filterNotNull().first()
        app.babySession.first { it == null }
        onStop()
    }
    // Leaving the screen must not stop monitoring: back just moves the app out of the way.
    BackHandler { activity?.moveTaskToBack(true) }
    // Kept here, so a pairing request shown in between does not light the screen up again.
    var screenOff by rememberSaveable { mutableStateOf(false) }
    val pairing = state.pairing
    if (pairing != null) {
        val pairingState by pairing.state.collectAsState()
        // A request that never reached the emojis is not worth showing (e.g. someone gave up).
        if (pairingState != PairingState.Connecting) {
            BackHandler { if (pairingState is PairingState.Comparing) session?.cancelPairing() else session?.dismissPairing() }
            Surface(Modifier.fillMaxSize()) {
                PairingContent(
                    pairingState,
                    onConfirm = pairing::confirm,
                    onReject = { session?.cancelPairing() },
                    onClose = { session?.dismissPairing() },
                )
            }
            return
        }
    }
    BabyScreen(state, session, missing, screenOff, { screenOff = it }, stop, onOpenSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BabyScreen(
    state: BabyState,
    session: BabySession?,
    missing: List<String>,
    screenOff: Boolean,
    onScreenOff: (Boolean) -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (screenOff) {
        ScreenOff(onWake = { onScreenOff(false) })
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.role_baby)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (missing.isNotEmpty()) {
                    PermissionHint(missing, baby = true)
                    Spacer(Modifier.weight(1f))
                } else {
                    // The baby's own picture, so the phone can be placed well.
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black, MaterialTheme.shapes.large),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Only while the screen is really visible: the preview keeps the camera on.
                        val visible = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (session != null && state.cameraAvailable && visible) {
                            VideoView(session.eglBase, session::attachPreview, session::detachPreview, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    val parents = state.parents
                    Text(
                        stringResource(if (parents.isNotEmpty()) R.string.baby_streaming else R.string.baby_waiting),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        when {
                            state.failed -> stringResource(R.string.baby_failed)
                            !state.cameraAvailable -> stringResource(R.string.baby_no_camera)
                            state.cameraError -> stringResource(R.string.baby_camera_error)
                            parents.isNotEmpty() -> stringResource(R.string.baby_connected_to, parents.joinToString(", "))
                            else -> stringResource(R.string.baby_no_parents)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.failed || state.cameraError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.canSwitchCamera) {
                        IconButton(onClick = { session?.switchCamera() }) {
                            Icon(Icons.Outlined.Cameraswitch, contentDescription = stringResource(R.string.baby_switch_camera))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { onScreenOff(true) }, enabled = missing.isEmpty()) {
                        Text(stringResource(R.string.baby_screen_off))
                    }
                    Button(onClick = onStop) { Text(stringResource(R.string.baby_stop)) }
                }
            }
        }
    }
}

/**
 * Black screen at the lowest brightness so it does not light up the room; monitoring continues.
 * Any tap brings the normal screen back.
 */
@Composable
private fun ScreenOff(onWake: () -> Unit) {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose { }
        val before = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = before } }
    }
    BackHandler(onBack = onWake)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.baby_screen_off_hint),
            color = Color.White.copy(alpha = 0.25f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
