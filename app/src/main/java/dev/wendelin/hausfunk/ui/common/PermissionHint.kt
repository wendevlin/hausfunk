package dev.wendelin.hausfunk.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.compat.Permissions

/** Permissions the role needs that are still missing; re-checked whenever the screen resumes. */
@Composable
fun rememberMissingPermissions(baby: Boolean): List<String> {
    val context = LocalContext.current
    var missing by rememberSaveable { mutableStateOf(Permissions.missingForRole(context, baby)) }
    LifecycleResumeEffect(baby) {
        missing = Permissions.missingForRole(context, baby)
        onPauseOrDispose { }
    }
    return missing
}

/**
 * Explains that the role cannot work without [missing] and offers to grant them. After the first
 * request the system may no longer show its dialog, so the button then opens the app settings.
 */
@Composable
fun PermissionHint(missing: List<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
    }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.permissions_missing),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = {
                    if (asked) {
                        context.startActivity(Permissions.appSettingsIntent(context))
                    } else {
                        launcher.launch(missing.toTypedArray())
                    }
                },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(if (asked) R.string.action_open_settings else R.string.action_allow))
            }
        }
    }
}
