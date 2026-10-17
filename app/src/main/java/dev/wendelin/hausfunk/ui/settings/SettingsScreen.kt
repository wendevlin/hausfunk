package dev.wendelin.hausfunk.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.data.DeviceName
import dev.wendelin.hausfunk.data.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.data.TrustedDevice
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceName: String,
    deviceId: String?,
    pairedDevices: List<TrustedDevice>,
    alarmGraceSeconds: Int,
    onBack: () -> Unit,
    onAlarmGrace: (Int) -> Unit,
    alarmSound: String?,
    onAlarmSound: (String?) -> Unit,
    onRenameSelf: (String) -> Unit,
    onRenamePeer: (deviceId: String, name: String) -> Unit,
    onRemovePeer: (deviceId: String) -> Unit,
) {
    var renamingSelf by rememberSaveable { mutableStateOf(false) }
    var renamingPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
            ) {
                item { SectionHeader(stringResource(R.string.settings_this_device)) }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.device_name_label)) },
                        supportingContent = { Text(deviceName) },
                        trailingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        modifier = Modifier.clickable { renamingSelf = true },
                    )
                }
                if (deviceId != null) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_device_id)) },
                            supportingContent = {
                                Text(deviceId.chunked(4).take(8).joinToString(" "), fontFamily = FontFamily.Monospace)
                            },
                        )
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item { SectionHeader(stringResource(R.string.settings_parent)) }
                item { AlarmGrace(alarmGraceSeconds, onAlarmGrace) }
                item { AlarmSound(alarmSound, onAlarmSound) }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item { SectionHeader(stringResource(R.string.settings_paired_devices)) }
                if (pairedDevices.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_paired_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(pairedDevices, key = { it.deviceId }) { device ->
                    val date = dateFormat.format(Date(device.pairedAt))
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (device.role == Role.BABY) R.string.settings_paired_baby else R.string.settings_paired_parent,
                                    date,
                                ),
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { renamingPeerId = device.deviceId }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_rename))
                                }
                                IconButton(onClick = { removingPeerId = device.deviceId }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_remove))
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (renamingSelf) {
        NameDialog(
            initial = deviceName,
            onDismiss = { renamingSelf = false },
            onSave = {
                onRenameSelf(it)
                renamingSelf = false
            },
        )
    }
    pairedDevices.firstOrNull { it.deviceId == renamingPeerId }?.let { device ->
        NameDialog(
            initial = device.name,
            onDismiss = { renamingPeerId = null },
            onSave = {
                onRenamePeer(device.deviceId, it)
                renamingPeerId = null
            },
        )
    }
    pairedDevices.firstOrNull { it.deviceId == removingPeerId }?.let { device ->
        AlertDialog(
            onDismissRequest = { removingPeerId = null },
            title = { Text(stringResource(R.string.settings_remove_title, device.name)) },
            text = { Text(stringResource(R.string.settings_remove_body, device.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemovePeer(device.deviceId)
                    removingPeerId = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removingPeerId = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** How long a lost connection may stay lost before the alarm rings, in 5 s steps. */
@Composable
private fun AlarmGrace(seconds: Int, onChange: (Int) -> Unit) {
    var value by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
    val range = Settings.ALARM_GRACE_RANGE
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_alarm_grace)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_alarm_grace_value, value.roundToInt()))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first) / 5 - 1,
                    onValueChangeFinished = { onChange(value.roundToInt()) },
                )
            }
        },
    )
}

/** The alarm's sound, chosen with the system's sound picker; "Default" means the phone's alarm sound. */
@Composable
private fun AlarmSound(uri: String?, onChange: (String?) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(PickAlarmSound) { picked ->
        // Null means the picker was cancelled; the default entry comes back as its own URI.
        if (picked != null) onChange(picked.takeUnless { it == DEFAULT_ALARM_ALERT_URI }?.toString())
    }
    // Read from the media database, off the main thread (a Ringtone would open a player for it).
    val title by produceState<String?>(null, uri) {
        value = uri?.let { soundTitle(context, it.toUri()) }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_alarm_sound)) },
        supportingContent = { Text(title ?: stringResource(R.string.settings_alarm_sound_default)) },
        modifier = Modifier.clickable { picker.launch(uri?.toUri()) },
    )
}

private suspend fun soundTitle(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.TITLE), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}

/** The system's alarm sound picker, without "Silent": an alarm nobody hears is no alarm. */
private object PickAlarmSound : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, DEFAULT_ALARM_ALERT_URI)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, input ?: DEFAULT_ALARM_ALERT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) {
            IntentCompat.getParcelableExtra(intent ?: return null, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            null
        }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = DeviceName.sanitize(it) },
                label = { Text(stringResource(R.string.device_name_label)) },
                supportingText = {
                    Text(stringResource(R.string.device_name_counter, name.length, DeviceName.MAX_LENGTH))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = DeviceName.isValid(name)) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
