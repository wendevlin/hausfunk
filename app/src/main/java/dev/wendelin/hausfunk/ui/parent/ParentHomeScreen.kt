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
import androidx.compose.material.icons.outlined.Add
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
    var adding by rememberSaveable { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<ParentPairing?>(null) }
    val babies = trusted.filter { it.role == Role.BABY }
    val trustedIds = trusted.map { it.deviceId }.toSet()

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
    fun endAdding() {
        pairing?.cancel()
        pairing = null
        adding = false
    }

    val p = pairing
    if (p != null) {
        val pairingState by p.state.collectAsState()
        BackHandler { endAdding() }
        Surface(Modifier.fillMaxSize()) {
            PairingContent(pairingState, onConfirm = p::confirm, onReject = ::endAdding, onClose = ::endAdding)
        }
        return
    }
    if (adding) {
        BackHandler { endAdding() }
        AddBabyScreen(
            candidates = found.filter { it.pairable && it.deviceId !in trustedIds },
            missing = missing,
            onBack = ::endAdding,
            onSelect = ::pair,
        )
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
        babies = babies,
        onlineIds = found.map { it.deviceId }.toSet(),
        missing = missing,
        selectedId = selectedId,
        twoPane = twoPane,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onSelect = ::watch,
        onAdd = { adding = true },
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
 * Paired baby devices, online when seen on the Wi-Fi. Medium and expanded widths show the watch
 * pane beside the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    babies: List<TrustedDevice>,
    onlineIds: Set<String>,
    missing: List<String>,
    selectedId: String?,
    twoPane: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelect: (deviceId: String) -> Unit,
    onAdd: () -> Unit,
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
                if (babies.isEmpty()) {
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
                        items(babies, key = { it.deviceId }) { baby ->
                            val online = baby.deviceId in onlineIds
                            ListItem(
                                headlineContent = { Text(baby.name) },
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            when {
                                                baby.deviceId == selectedId -> R.string.parent_watching
                                                online -> R.string.parent_online
                                                else -> R.string.parent_offline
                                            },
                                        ),
                                    )
                                },
                                modifier = if (online) Modifier.clickable { onSelect(baby.deviceId) } else Modifier,
                            )
                        }
                    }
                }
                Button(
                    onClick = onAdd,
                    enabled = missing.isEmpty(),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.parent_add), modifier = Modifier.padding(start = 8.dp))
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

/** Baby devices whose pairing window is open and that are not paired yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBabyScreen(candidates: List<FoundBaby>, missing: List<String>, onBack: () -> Unit, onSelect: (FoundBaby) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.parent_add)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (missing.isNotEmpty()) PermissionHint(missing, Modifier.padding(16.dp))
            Text(
                stringResource(R.string.parent_add_hint),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
            if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.parent_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(candidates, key = { it.deviceId }) { baby ->
                    ListItem(
                        headlineContent = { Text(baby.name) },
                        supportingContent = { Text(stringResource(R.string.parent_tap_to_pair)) },
                        modifier = Modifier.clickable { onSelect(baby) },
                    )
                }
            }
        }
    }
}
