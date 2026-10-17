package dev.wendelin.hausfunk.ui.parent

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.R
import kotlinx.coroutines.delay

/** A volume as it matters for monitoring: muted, too quiet to rely on, or fine. */
enum class Loudness { MUTED, QUIET, OK }

/** Below this share of the maximum, a crying baby may go unheard. */
private const val QUIET_SHARE = 0.25f
private const val POLL_MS = 1_000L

/**
 * Re-reads [stream]'s volume every second while shown (Android has no public callback for volume
 * changes). The baby's sound plays as media ([AudioManager.STREAM_MUSIC]), the connection-loss
 * alarm on [AudioManager.STREAM_ALARM].
 */
@Composable
fun rememberLoudness(stream: Int): Loudness {
    val audio = LocalContext.current.getSystemService(AudioManager::class.java)
    var loudness by remember { mutableStateOf(Loudness.OK) }
    LaunchedEffect(audio, stream) {
        while (true) {
            val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
            val volume = audio.getStreamVolume(stream)
            loudness = when {
                volume == 0 || audio.isStreamMute(stream) -> Loudness.MUTED
                volume.toFloat() / max < QUIET_SHARE -> Loudness.QUIET
                else -> Loudness.OK
            }
            delay(POLL_MS)
        }
    }
    return loudness
}

/** A small banner on the watch screen when [stream] is muted or very quiet, with "Louder". */
@Composable
fun VolumeWarning(stream: Int, modifier: Modifier = Modifier) {
    val loudness = rememberLoudness(stream)
    if (loudness == Loudness.OK) return
    val audio = LocalContext.current.getSystemService(AudioManager::class.java)
    Row(
        modifier
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.VolumeOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            stringResource(
                when {
                    stream == AudioManager.STREAM_ALARM && loudness == Loudness.MUTED -> R.string.watch_alarm_muted
                    stream == AudioManager.STREAM_ALARM -> R.string.watch_alarm_quiet
                    loudness == Loudness.MUTED -> R.string.watch_volume_muted
                    else -> R.string.watch_volume_quiet
                },
            ),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        TextButton(
            onClick = {
                // One step up, with the system volume slider so the change is visible.
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            },
        ) {
            Text(stringResource(R.string.watch_volume_louder))
        }
    }
}
