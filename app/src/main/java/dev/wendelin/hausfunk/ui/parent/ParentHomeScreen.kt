package dev.wendelin.hausfunk.ui.parent

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import dev.wendelin.hausfunk.data.SoundPrefs
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.data.TrustedDevice
import dev.wendelin.hausfunk.discovery.FoundBaby
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.media.ParentState
import dev.wendelin.hausfunk.pairing.ParentPairing
import dev.wendelin.hausfunk.service.ParentService
import dev.wendelin.hausfunk.ui.common.AutoPictureInPicture
import dev.wendelin.hausfunk.ui.common.PermissionHint
import dev.wendelin.hausfunk.ui.common.VideoView
import dev.wendelin.hausfunk.ui.common.rememberInPictureInPicture
import dev.wendelin.hausfunk.ui.common.rememberMissingPermissions
import dev.wendelin.hausfunk.ui.pairing.PairingContent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The parent screens: the list of baby devices, the watch screen and pairing. Watching itself runs
 * in [ParentService], so sound continues with the screen off and in picture-in-picture. One baby
 * at a time, one screen at a time, on phones and tablets alike.
 */
@Composable
fun ParentRoute(
    app: HausfunkApplication,
    deviceName: String,
    trusted: List<TrustedDevice>,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val missing = rememberMissingPermissions(baby = false)
    val identity by produceState<Identity?>(null) { value = app.identity.await() }
    val finder = app.babyFinder
    if (missing.isEmpty()) {
        DisposableEffect(Unit) {
            finder.start()
            onDispose { finder.stop() }
        }
    }
    val found by finder.babies.collectAsState()
    val session by app.parentSession.collectAsState()
    val state by remember(session) { session?.state ?: MutableStateFlow(ParentState.Idle) }.collectAsState()
    val watchedId = state.babyId
    var pairing by remember { mutableStateOf<ParentPairing?>(null) }
    // Pairing is mutual and independent of roles: any paired device may be the baby today.
    val trustedIds = trusted.map { it.deviceId }.toSet()
    val newBabies = found.filter { it.deviceId !in trustedIds }

    fun watch(deviceId: String) {
        // Tapping the baby that is already being watched must not start over; it reconnects on its own.
        if (deviceId == watchedId) return
        val baby = found.firstOrNull { it.deviceId == deviceId } ?: return
        ParentService.watch(context, baby)
    }
    fun stopWatching() = ParentService.stop(context)
    fun pair(baby: FoundBaby) {
        val id = identity ?: return
        val p = ParentPairing(id, deviceName, app::currentTrustedIds) { app.storePaired(it, Role.BABY) }
        pairing = p
        p.start(baby, app.appScope)
    }
    fun endPairing() {
        pairing?.cancel()
        pairing = null
    }

    val p = pairing
    if (p != null) {
        val pairingState by p.state.collectAsState()
        BackHandler { endPairing() }
        Surface(Modifier.fillMaxSize()) {
            PairingContent(pairingState, onConfirm = p::confirm, onReject = ::endPairing, onClose = ::endPairing)
        }
        return
    }

    val s = session
    if (s != null && watchedId != null) {
        var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
        var videoBounds by remember { mutableStateOf<Rect?>(null) }
        val inPip = rememberInPictureInPicture()
        AutoPictureInPicture(enabled = state is ParentState.Connected, aspectRatio = aspectRatio, sourceRect = videoBounds)
        if (inPip) {
            // Picture-in-picture: the video and nothing else.
            Box(Modifier.fillMaxSize().background(WatchColors.background)) {
                VideoView(s.eglBase, s::attach, s::detach, Modifier.fillMaxSize(), onAspectRatio = { aspectRatio = it })
                // Plus the last sound, and a red dot while the connection is down.
                val lastSound by s.lastSound.collectAsState()
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
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
            return
        }
        BackHandler { stopWatching() }
        val settings by app.settings.settings.collectAsState(initial = null)
        WatchScreen(
            s,
            state,
            soundPrefs = settings?.soundFor(watchedId) ?: SoundPrefs(),
            onSoundPrefs = { prefs -> app.appScope.launch { app.settings.setSound(watchedId, prefs) } },
            onClose = ::stopWatching,
            onAspectRatio = { aspectRatio = it },
            onVideoBounds = { videoBounds = it },
        )
        return
    }
    ParentHomeScreen(
        paired = trusted,
        newBabies = newBabies,
        onlineIds = found.map { it.deviceId }.toSet(),
        missing = missing,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onSelect = ::watch,
        onPair = ::pair,
    )
}

/**
 * Paired devices, online while they run as baby on this Wi-Fi, and below them baby devices that
 * are not paired yet (tap to pair).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    paired: List<TrustedDevice>,
    newBabies: List<FoundBaby>,
    onlineIds: Set<String>,
    missing: List<String>,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelect: (deviceId: String) -> Unit,
    onPair: (FoundBaby) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.parent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
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
                    .widthIn(max = 640.dp),
            ) {
                if (missing.isNotEmpty()) PermissionHint(missing, Modifier.padding(16.dp))
                if (paired.isEmpty() && newBabies.isEmpty()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.parent_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        if (paired.isNotEmpty()) item(key = "paired") { SectionTitle(stringResource(R.string.parent_paired_section)) }
                        items(paired, key = { it.deviceId }) { device ->
                            val online = device.deviceId in onlineIds
                            ListItem(
                                headlineContent = { Text(device.name) },
                                supportingContent = {
                                    Text(stringResource(if (online) R.string.parent_online else R.string.parent_offline))
                                },
                                modifier = if (online) Modifier.clickable { onSelect(device.deviceId) } else Modifier,
                            )
                        }
                        if (newBabies.isNotEmpty()) item(key = "new") { SectionTitle(stringResource(R.string.parent_new_section)) }
                        items(newBabies, key = { "new-" + it.deviceId }) { baby ->
                            ListItem(
                                headlineContent = { Text(baby.name) },
                                supportingContent = { Text(stringResource(R.string.parent_tap_to_pair)) },
                                modifier = Modifier.clickable { onPair(baby) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
