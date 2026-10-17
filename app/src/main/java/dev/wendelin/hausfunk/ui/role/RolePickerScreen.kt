package dev.wendelin.hausfunk.ui.role

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.ui.theme.LogoMark

/**
 * Baby or parent, as two list entries that always stay in the same order (muscle memory). The
 * last used role is marked with a border and a label, not a different colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RolePickerScreen(
    lastRole: Role?,
    showIdentityResetNotice: Boolean,
    onRoleChosen: (Role) -> Unit,
    onDismissIdentityResetNotice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    LogoMark(Modifier.padding(start = 16.dp, end = 8.dp).size(width = 32.dp, height = 24.dp))
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
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.role_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                RoleItem(
                    Icons.Outlined.ChildCare, R.string.role_baby, R.string.role_baby_description,
                    lastUsed = lastRole == Role.BABY, onClick = { onRoleChosen(Role.BABY) },
                )
                RoleItem(
                    Icons.Outlined.Hearing, R.string.role_parent, R.string.role_parent_description,
                    lastUsed = lastRole == Role.PARENT, onClick = { onRoleChosen(Role.PARENT) },
                )
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
private fun RoleItem(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    lastUsed: Boolean,
    onClick: () -> Unit,
) {
    val border = if (lastUsed) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    OutlinedCard(onClick = onClick, border = border, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Column(
                Modifier
                    .padding(start = 20.dp)
                    .weight(1f),
            ) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (lastUsed) {
                Text(
                    stringResource(R.string.role_last_used),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
