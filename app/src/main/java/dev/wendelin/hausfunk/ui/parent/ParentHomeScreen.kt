package dev.wendelin.hausfunk.ui.parent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.data.TrustedDevice
import dev.wendelin.hausfunk.discovery.FoundBaby
import dev.wendelin.hausfunk.ui.common.PermissionHint

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
                if (missing.isNotEmpty()) PermissionHint(missing, baby = false, modifier = Modifier.padding(16.dp))
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
