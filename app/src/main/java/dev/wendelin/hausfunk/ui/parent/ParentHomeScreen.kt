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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
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
import dev.wendelin.hausfunk.media.ParentSession
import dev.wendelin.hausfunk.media.ParentState
import dev.wendelin.hausfunk.signaling.Hello
import dev.wendelin.hausfunk.signaling.PROTOCOL_VERSION
import dev.wendelin.hausfunk.signaling.PlainTcpTransport
import dev.wendelin.hausfunk.ui.common.PermissionHint
import dev.wendelin.hausfunk.ui.common.appVersion
import dev.wendelin.hausfunk.ui.common.rememberMissingPermissions
import kotlinx.coroutines.launch

/**
 * Runs discovery and the parent session while the parent screens are shown (phase 2; phase 4
 * moves the session into a foreground service). Home and watch share this route so the session
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
    val finder = remember { BabyFinder(context) }
    val session = remember { ParentSession(context, PlainTcpTransport()) }
    DisposableEffect(Unit) {
        onDispose {
            session.release()
            finder.release()
        }
    }
    if (missing.isEmpty()) {
        DisposableEffect(Unit) {
            finder.start()
            onDispose { finder.stop() }
        }
    }
    val found by finder.babies.collectAsState()
    val state by session.state.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    fun watch(baby: FoundBaby) {
        selectedId = baby.deviceId
        app.appScope.launch {
            val hello = Hello(PROTOCOL_VERSION, app.identity.await().deviceId, deviceName, appVersion(context), watching = true)
            session.connect(baby, hello)
        }
    }
    fun stopWatching() {
        selectedId = null
        session.disconnect()
    }

    val twoPane = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    val watching = selectedId != null
    if (watching && !twoPane) {
        BackHandler { stopWatching() }
        WatchScreen(session, state, onRetry = { found.firstOrNull { it.deviceId == selectedId }?.let(::watch) }, onClose = ::stopWatching)
        return
    }
    ParentHomeScreen(
        babies = trusted.filter { it.role == Role.BABY },
        nearby = found,
        missing = missing,
        selectedId = selectedId,
        twoPane = twoPane,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onSelect = ::watch,
        watchPane = {
            if (watching) {
                WatchScreen(session, state, onRetry = { found.firstOrNull { it.deviceId == selectedId }?.let(::watch) }, onClose = ::stopWatching)
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
 * Paired baby devices from the trust store, and (phase 2 only) unpaired baby devices found on the
 * Wi-Fi. Medium and expanded widths show the watch pane beside the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    babies: List<TrustedDevice>,
    nearby: List<FoundBaby>,
    missing: List<String>,
    selectedId: String?,
    twoPane: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelect: (FoundBaby) -> Unit,
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
            BabyList(
                babies,
                nearby,
                missing,
                selectedId,
                onSelect,
                Modifier
                    .fillMaxHeight()
                    .then(if (twoPane) Modifier.width(360.dp) else Modifier.fillMaxWidth()),
            )
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
private fun BabyList(
    babies: List<TrustedDevice>,
    nearby: List<FoundBaby>,
    missing: List<String>,
    selectedId: String?,
    onSelect: (FoundBaby) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        if (missing.isNotEmpty()) {
            PermissionHint(missing, Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            if (babies.isEmpty()) {
                item(key = "paired-empty") {
                    Text(
                        stringResource(R.string.parent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(babies, key = { it.deviceId }) { baby ->
                ListItem(
                    headlineContent = { Text(baby.name) },
                    supportingContent = { Text(stringResource(R.string.parent_offline)) },
                )
            }
            // TEMPORARY, phase 2 only: unpaired devices, until pairing arrives in phase 3.
            item(key = "nearby-header") {
                Text(
                    stringResource(R.string.parent_nearby),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            if (nearby.isEmpty()) {
                item(key = "nearby-empty") {
                    Text(
                        stringResource(R.string.parent_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(nearby, key = { "nearby-" + it.deviceId }) { baby ->
                ListItem(
                    headlineContent = { Text(baby.name) },
                    supportingContent = {
                        Text(stringResource(if (baby.deviceId == selectedId) R.string.parent_watching else R.string.parent_online))
                    },
                    modifier = Modifier.clickable { onSelect(baby) },
                )
            }
        }
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .align(Alignment.End)
                .padding(16.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.parent_add), modifier = Modifier.padding(start = 8.dp))
        }
    }
}
