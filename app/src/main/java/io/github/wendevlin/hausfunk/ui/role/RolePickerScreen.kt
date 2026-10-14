package io.github.wendevlin.hausfunk.ui.role

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.wendevlin.hausfunk.R
import io.github.wendevlin.hausfunk.data.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RolePickerScreen(
    lastRole: Role?,
    showIdentityResetNotice: Boolean,
    windowSizeClass: WindowSizeClass,
    onRoleChosen: (Role) -> Unit,
    onDismissIdentityResetNotice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Side by side on wide windows and on phones in landscape, stacked otherwise.
    val sideBySide = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact ||
        windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
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
                    .widthIn(max = 840.dp)
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.role_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                val baby: @Composable (Modifier) -> Unit = {
                    RoleCard(
                        Icons.Outlined.ChildCare, R.string.role_baby, R.string.role_baby_description,
                        highlighted = lastRole == Role.BABY, onClick = { onRoleChosen(Role.BABY) }, modifier = it,
                    )
                }
                val parent: @Composable (Modifier) -> Unit = {
                    RoleCard(
                        Icons.Outlined.Hearing, R.string.role_parent, R.string.role_parent_description,
                        highlighted = lastRole == Role.PARENT, onClick = { onRoleChosen(Role.PARENT) }, modifier = it,
                    )
                }
                // The last used role comes first.
                val ordered = if (lastRole == Role.PARENT) listOf(parent, baby) else listOf(baby, parent)
                if (sideBySide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ordered.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ordered.forEach { it(Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        }
    }

    if (showIdentityResetNotice) {
        AlertDialog(
            onDismissRequest = onDismissIdentityResetNotice,
            title = { Text(stringResource(R.string.identity_reset_title)) },
            text = { Text(stringResource(R.string.identity_reset_body)) },
            confirmButton = {
                TextButton(onClick = onDismissIdentityResetNotice) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = if (highlighted) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    Card(onClick = onClick, colors = colors, shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (highlighted) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.role_last_used), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
