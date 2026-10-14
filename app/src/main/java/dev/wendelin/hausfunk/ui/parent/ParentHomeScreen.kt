package dev.wendelin.hausfunk.ui.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.wendelin.hausfunk.discovery.BabyFinder
import dev.wendelin.hausfunk.discovery.FoundBaby
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.media.ParentSession
import dev.wendelin.hausfunk.pairing.ParentPairing
import dev.wendelin.hausfunk.signaling.Hello
import dev.wendelin.hausfunk.signaling.PROTOCOL_VERSION
import dev.wendelin.hausfunk.ui.common.PermissionHint
import dev.wendelin.hausfunk.ui.common.appVersion
import dev.wendelin.hausfunk.ui.common.rememberMissingPermissions
import dev.wendelin.hausfunk.ui.pairing.PairingContent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Runs discovery and the parent session while the parent screens are shown (phase 4 moves the
 * session into a foreground service). Home, watch and pairing share this route so the session
 * survives switching between them and the two-pane layout.
 */
@Composable
fun ParentRoute(
    app: HausfunkApplication,
    deviceName: String,
    trusted: List<TrustedDevice>,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val missing = rememberMissingPermissions(baby = false)
    val identity by produceState<Identity?>(null) { value = app.identity.await() }
    val finder = remember { BabyFinder(context) }
    DisposableEffect(Unit) { onDispose { finder.release() } }
    val session = remember(identity) { identity?.let { ParentSession(context, it, app::currentTrustedIds) } }
    DisposableEffect(session) { onDispose { session?.release() } }
    LaunchedEffect(session) {
        // A baby removed in the settings is disconnected at once.
        if (session != null) app.trustedIds.filterNotNull().collect(session::onTrustChanged)
    }
    if (missing.isEmpty()) {
        DisposableEffect(Unit) {
            finder.start()
            onDispose { finder.stop() }
        }
    }
    val found by finder.babies.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf<ParentPairing?>(null) }
    // Pairing is mutual and independent of roles: any paired device may be the baby today.
    val trustedIds = trusted.map { it.deviceId }.toSet()
    val newBabies = found.filter { it.deviceId !in trustedIds }

    fun watch(deviceId: String) {
        val baby = found.firstOrNull { it.deviceId == deviceId } ?: return
        val s = session ?: return
        selectedId = deviceId
        app.appScope.launch {
            val hello = Hello(PROTOCOL_VERSION, app.identity.await().deviceId, deviceName, appVersion(context), watching = true)
            s.connect(baby, hello)
        }
    }
    fun stopWatching() {
        selectedId = null
        session?.disconnect()
    }
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
    val twoPane = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    val watching = selectedId != null && s != null
    if (watching && !twoPane) {
        val state by s.state.collectAsState()
        BackHandler { stopWatching() }
        WatchScreen(s, state, onRetry = { selectedId?.let(::watch) }, onClose = ::stopWatching)
        return
    }
    ParentHomeScreen(
        paired = trusted,
        newBabies = newBabies,
        onlineIds = found.map { it.deviceId }.toSet(),
        missing = missing,
        selectedId = selectedId,
        twoPane = twoPane,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onSelect = ::watch,
        onPair = ::pair,
        watchPane = {
            if (watching) {
                val state by s.state.collectAsState()
                WatchScreen(s, state, onRetry = { selectedId?.let(::watch) }, onClose = ::stopWatching)
            } else {
                Text(
                    stringResource(R.string.parent_select_hint),
                    color = WatchColors.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        },
    )
}

/**
 * Paired devices, online while they run as baby on this Wi-Fi, and below them baby devices that
 * are not paired yet (tap to pair). Medium and expanded widths show the watch pane beside the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    paired: List<TrustedDevice>,
    newBabies: List<FoundBaby>,
    onlineIds: Set<String>,
    missing: List<String>,
    selectedId: String?,
    twoPane: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelect: (deviceId: String) -> Unit,
    onPair: (FoundBaby) -> Unit,
    watchPane: @Composable () -> Unit,
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
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .then(if (twoPane) Modifier.width(360.dp) else Modifier.fillMaxWidth()),
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
                                    Text(
                                        stringResource(
                                            when {
                                                device.deviceId == selectedId -> R.string.parent_watching
                                                online -> R.string.parent_online
                                                else -> R.string.parent_offline
                                            },
                                        ),
                                    )
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
            if (twoPane) {
                // The watch screen is always dark so it never lights up a bedroom.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 8.dp, end = 16.dp, bottom = 16.dp)
                        .background(WatchColors.background, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    watchPane()
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
