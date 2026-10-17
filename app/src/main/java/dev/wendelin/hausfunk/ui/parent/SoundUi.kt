package dev.wendelin.hausfunk.ui.parent

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.data.SoundPrefs
import dev.wendelin.hausfunk.media.BabyStatus
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The bottom of the level scale; quieter rooms all look empty. */
private const val SCALE_MIN_DBFS = -80f

private fun fraction(dbfs: Float) = ((dbfs - SCALE_MIN_DBFS) / -SCALE_MIN_DBFS).coerceIn(0f, 1f)

/** A thin bar following the sound level, with the threshold as a tick. */
@Composable
fun LevelBar(
    level: Float?,
    thresholdDbfs: Float,
    modifier: Modifier = Modifier,
    track: Color = Color.White.copy(alpha = 0.15f),
    quiet: Color = Color.White.copy(alpha = 0.7f),
    loud: Color = MaterialTheme.colorScheme.tertiary,
    tick: Color = Color.White,
) {
    val shown by animateFloatAsState(fraction(level ?: SCALE_MIN_DBFS), tween(150), label = "level")
    val above = level != null && level >= thresholdDbfs
    Canvas(modifier) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(if (above) loud else quiet, size = Size(size.width * shown, size.height), cornerRadius = radius)
        val x = size.width * fraction(thresholdDbfs)
        val w = 2.dp.toPx()
        drawRect(tick, topLeft = Offset(x - w / 2, -size.height / 2), size = Size(w, size.height * 2))
    }
}

/** "Last sound 4 minutes ago", kept current. [lastSound] is elapsed time. */
@Composable
fun lastSoundText(lastSound: Long?): String {
    val now by produceState(SystemClock.elapsedRealtime(), lastSound) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(10_000)
        }
    }
    if (lastSound == null) return stringResource(R.string.watch_no_sound_yet)
    val age = (now - lastSound).coerceAtLeast(0)
    if (age < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.watch_last_sound_now)
    val wallNow = System.currentTimeMillis()
    val relative = DateUtils.getRelativeTimeSpanString(wallNow - age, wallNow, DateUtils.MINUTE_IN_MILLIS)
    return stringResource(R.string.watch_last_sound, relative)
}

/** Level bar, last sound and the baby's battery, under the video. */
@Composable
fun SoundStatus(
    level: Float?,
    thresholdDbfs: Float,
    lastSound: Long?,
    status: BabyStatus?,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        LevelBar(level, thresholdDbfs, Modifier.fillMaxWidth().height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (muted) {
                    // The gate is closed: quiet sounds are not played.
                    Icon(
                        Icons.AutoMirrored.Outlined.VolumeOff,
                        contentDescription = stringResource(R.string.watch_gate_closed),
                        tint = WatchColors.onBackground,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp),
                    )
                }
                Text(lastSoundText(lastSound), color = WatchColors.onBackground, style = MaterialTheme.typography.bodySmall)
            }
            if (status != null && status.battery >= 0) {
                val low = status.battery < 20 && !status.charging
                Text(
                    stringResource(
                        if (status.charging) R.string.watch_battery_charging else R.string.watch_battery,
                        status.battery,
                    ),
                    color = if (low) MaterialTheme.colorScheme.error else WatchColors.onBackground,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (status?.hot == true) {
            Text(
                stringResource(R.string.watch_baby_hot),
                color = WatchColors.onBackground,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Threshold beside the live level, the gate switch and its hold time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSettingsSheet(
    level: Float?,
    prefs: SoundPrefs,
    /** Each change only touches its own field of the stored settings, so quick changes never undo each other. */
    onChange: ((SoundPrefs) -> SoundPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    // Sliders move freely; the setting is stored when the finger lifts.
    var threshold by remember(prefs.thresholdDbfs) { mutableFloatStateOf(prefs.thresholdDbfs) }
    var hold by remember(prefs.holdSeconds) { mutableFloatStateOf(prefs.holdSeconds.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.sound_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.sound_threshold_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            LevelBar(
                level,
                threshold,
                Modifier.fillMaxWidth().height(10.dp),
                track = MaterialTheme.colorScheme.surfaceVariant,
                quiet = MaterialTheme.colorScheme.secondary,
                loud = MaterialTheme.colorScheme.tertiary,
                tick = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.sound_threshold, threshold.roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Slider(
                value = fraction(threshold),
                onValueChange = { threshold = SCALE_MIN_DBFS - SCALE_MIN_DBFS * it },
                valueRange = fraction(SoundPrefs.THRESHOLD_RANGE.start)..fraction(SoundPrefs.THRESHOLD_RANGE.endInclusive),
                onValueChangeFinished = {
                    val value = threshold.roundToInt().toFloat()
                    onChange { it.copy(thresholdDbfs = value) }
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.sound_gate), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = prefs.gate, onCheckedChange = { on -> onChange { it.copy(gate = on) } })
            }
            if (prefs.gate) {
                Text(
                    stringResource(R.string.sound_hold, hold.roundToInt()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Slider(
                    value = hold,
                    onValueChange = { hold = it },
                    valueRange = SoundPrefs.HOLD_RANGE.first.toFloat()..SoundPrefs.HOLD_RANGE.last.toFloat(),
                    onValueChangeFinished = {
                        val seconds = hold.roundToInt()
                        onChange { it.copy(holdSeconds = seconds) }
                    },
                )
            }
        }
    }
}
