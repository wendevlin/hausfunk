package dev.wendelin.hausfunk.ui.baby

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.media.BabySession
import dev.wendelin.hausfunk.media.BabyState
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.pairing.PairingState
import dev.wendelin.hausfunk.ui.pairing.PairingContent
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import dev.wendelin.hausfunk.ui.common.PermissionHint
import dev.wendelin.hausfunk.ui.common.appVersion
import dev.wendelin.hausfunk.ui.common.rememberMissingPermissions
import kotlinx.coroutines.launch

/**
 * Runs the baby session while the screen is shown (phase 4 moves it into a foreground service)
 * and only once every needed permission is granted. A pairing in progress takes over the screen.
 */
@Composable
fun BabyRoute(app: HausfunkApplication, deviceName: String, onStop: () -> Unit, onOpenSettings: () -> Unit) {
    val missing = rememberMissingPermissions(baby = true)
    var state by remember { mutableStateOf(BabyState()) }
    var session by remember { mutableStateOf<BabySession?>(null) }
    if (missing.isEmpty()) {
        val context = LocalContext.current
        val view = LocalView.current
        DisposableEffect(Unit) {
            val job = app.appScope.launch {
                val identity = app.identity.await()
                app.trustedIds.filterNotNull().first() // pinning needs the stored pairings
                val s = BabySession(context, identity, app::currentTrustedIds) { app.storePaired(it, Role.PARENT) }
                try {
                    session = s
                    s.start(deviceName, appVersion(context))
                    launch { app.trustedIds.filterNotNull().collect { s.onTrustChanged(it) } }
                    s.state.collect { state = it }
                } finally {
                    s.stop()
                }
            }
            // TEMPORARY until phase 4: the session lives in the activity, so the screen stays on.
            view.keepScreenOn = true
            onDispose {
                view.keepScreenOn = false
                job.cancel()
                session = null
                state = BabyState()
            }
        }
    }
    val pairing = state.pairing
    if (pairing != null) {
        val pairingState by pairing.state.collectAsState()
        BackHandler { if (pairingState is PairingState.Comparing || pairingState == PairingState.Connecting) session?.cancelPairing() else session?.dismissPairing() }
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
    BabyScreen(
        state,
        missing,
        onStop,
        onOpenSettings,
        onPair = { session?.openPairingWindow() },
        onCancelPairing = { session?.cancelPairing() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BabyScreen(
    state: BabyState,
    missing: List<String>,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onPair: () -> Unit,
    onCancelPairing: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.role_baby)) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.baby_switch_camera)) },
                                leadingIcon = { Icon(Icons.Outlined.Cameraswitch, contentDescription = null) },
                                enabled = false,
                                onClick = { menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_settings)) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                },
                            )
                        }
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
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (missing.isNotEmpty()) {
                        PermissionHint(missing)
                        return@Column
                    }
                    if (state.pairingWindowOpen) {
                        Text(stringResource(R.string.baby_pairing_open_title), style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.baby_pairing_open_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        return@Column
                    }
                    val parent = state.parentName
                    Text(
                        stringResource(if (parent != null) R.string.baby_streaming else R.string.baby_waiting),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            state.failed -> stringResource(R.string.baby_failed)
                            parent != null -> stringResource(R.string.baby_connected_to, parent)
                            else -> stringResource(R.string.baby_no_parents)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.cameraAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.baby_no_camera),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (state.pairingWindowOpen) {
                        OutlinedButton(onClick = onCancelPairing) { Text(stringResource(R.string.action_cancel)) }
                    } else {
                        OutlinedButton(onClick = onPair, enabled = missing.isEmpty() && !state.failed) {
                            Text(stringResource(R.string.baby_pair))
                        }
                    }
                    Button(onClick = onStop) { Text(stringResource(R.string.baby_stop)) }
                }
            }
        }
    }
}
