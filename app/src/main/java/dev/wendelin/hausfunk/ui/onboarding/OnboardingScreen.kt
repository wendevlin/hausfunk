package dev.wendelin.hausfunk.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.compat.Permissions
import dev.wendelin.hausfunk.compat.SystemDeviceName
import dev.wendelin.hausfunk.data.DeviceName
import dev.wendelin.hausfunk.ui.theme.LogoMark

private enum class Step { NAME, CAMERA, MICROPHONE, NOTIFICATIONS, BATTERY }

@Composable
fun OnboardingScreen(
    savedName: String,
    onNameChosen: (String) -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    // Decided once: steps for permissions that are already granted are left out.
    val steps = remember {
        buildList {
            add(Step.NAME)
            if (!Permissions.isGranted(context, Manifest.permission.CAMERA)) add(Step.CAMERA)
            if (!Permissions.isGranted(context, Manifest.permission.RECORD_AUDIO)) add(Step.MICROPHONE)
            if (Permissions.notificationPermissionNeeded && !Permissions.isGranted(context, Permissions.NOTIFICATIONS)) {
                add(Step.NOTIFICATIONS)
            }
            if (!Permissions.isIgnoringBatteryOptimizations(context)) add(Step.BATTERY)
        }
    }
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    // After process death the step list may be shorter than before.
    val index = savedIndex.coerceAtMost(steps.lastIndex)
    val next: () -> Unit = {
        if (index + 1 < steps.size) {
            savedIndex = index + 1
        } else {
            onFinished()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { next() }

    val stepLabel = stringResource(R.string.onboarding_step, index + 1, steps.size)
    when (steps[index]) {
        Step.NAME -> NameStep(
            stepLabel = stepLabel,
            initialName = savedName.ifEmpty { DeviceName.sanitize(SystemDeviceName.suggestion(context)) },
            onContinue = { name ->
                onNameChosen(name)
                next()
            },
        )
        Step.CAMERA -> PermissionStep(
            stepLabel, Icons.Outlined.Videocam, R.string.onboarding_camera_title, R.string.onboarding_camera_body,
            onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) }, onSkip = next,
        )
        Step.MICROPHONE -> PermissionStep(
            stepLabel, Icons.Outlined.Mic, R.string.onboarding_microphone_title, R.string.onboarding_microphone_body,
            onAllow = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }, onSkip = next,
        )
        Step.NOTIFICATIONS -> PermissionStep(
            stepLabel, Icons.Outlined.Notifications, R.string.onboarding_notifications_title,
            R.string.onboarding_notifications_body,
            onAllow = { permissionLauncher.launch(Permissions.NOTIFICATIONS) }, onSkip = next,
        )
        Step.BATTERY -> PermissionStep(
            stepLabel, Icons.Outlined.BatteryChargingFull, R.string.onboarding_battery_title,
            R.string.onboarding_battery_body,
            onAllow = { batteryLauncher.launch(Permissions.batteryOptimizationIntent(context)) }, onSkip = next,
        )
    }
}

/** Shared frame: step label on top, scrollable content, actions at the bottom end. */
@Composable
private fun StepFrame(
    stepLabel: String,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    stepLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.Center,
                ) { content() }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { actions() }
            }
        }
    }
}

@Composable
private fun NameStep(stepLabel: String, initialName: String, onContinue: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val valid = DeviceName.isValid(name)
    val submit: () -> Unit = {
        if (valid) onContinue(name.trim())
    }

    StepFrame(
        stepLabel = stepLabel,
        actions = { Button(onClick = submit, enabled = valid) { Text(stringResource(R.string.action_continue)) } },
    ) {
        LogoMark(Modifier.size(width = 56.dp, height = 42.dp))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_name_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_name_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = DeviceName.sanitize(it) },
            label = { Text(stringResource(R.string.device_name_label)) },
            supportingText = {
                Text(stringResource(R.string.device_name_counter, name.length, DeviceName.MAX_LENGTH))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PermissionStep(
    stepLabel: String,
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes body: Int,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    StepFrame(
        stepLabel = stepLabel,
        actions = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.action_not_now)) }
            Button(onClick = onAllow) { Text(stringResource(R.string.action_allow)) }
        },
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_later_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
