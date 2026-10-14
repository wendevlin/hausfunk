package io.github.wendevlin.hausfunk.ui.parent

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.wendevlin.hausfunk.R
import io.github.wendevlin.hausfunk.data.TrustedDevice

/**
 * Phase 1: lists paired baby devices from the trust store. Online state, pairing and
 * monitoring arrive in later phases. Medium and expanded widths show the (still empty)
 * watch pane beside the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentHomeScreen(
    babies: List<TrustedDevice>,
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val twoPane = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
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
                        .background(Color.Black, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.parent_select_hint),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BabyList(babies: List<TrustedDevice>, modifier: Modifier) {
    Column(modifier) {
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
                    ListItem(
                        headlineContent = { Text(baby.name) },
                        supportingContent = { Text(stringResource(R.string.parent_offline)) },
                    )
                }
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
