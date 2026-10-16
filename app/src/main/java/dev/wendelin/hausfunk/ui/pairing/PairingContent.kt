package dev.wendelin.hausfunk.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wendelin.hausfunk.pairing.PairingRunner
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.pairing.PairingState
import dev.wendelin.hausfunk.pairing.SasEmoji
import dev.wendelin.hausfunk.pairing.SasEmojis
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource

/**
 * The pairing exchange as both phones show it: the 4 emojis with their names, "They match" and
 * "Don't match"; then the result. [onClose] dismisses a finished exchange.
 */
@Composable
fun PairingContent(state: PairingState, onConfirm: () -> Unit, onReject: () -> Unit, onClose: () -> Unit) {
    // Edge to edge: keep the content and buttons clear of the status and navigation bars.
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                when (state) {
                    PairingState.Connecting -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.pairing_connecting), style = MaterialTheme.typography.bodyLarge)
                    }
                    is PairingState.Comparing -> Comparing(state)
                    is PairingState.Paired -> {
                        Text(stringResource(R.string.pairing_done_title), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.pairing_done_body, state.peerName), style = MaterialTheme.typography.bodyLarge)
                    }
                    PairingState.Failed -> {
                        Text(stringResource(R.string.pairing_failed_title), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.pairing_failed_body), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                when {
                    state is PairingState.Comparing && !state.confirmed -> {
                        OutlinedButton(onClick = onReject) { Text(stringResource(R.string.pairing_no_match)) }
                        Button(onClick = onConfirm) { Text(stringResource(R.string.pairing_match)) }
                    }
                    state is PairingState.Paired || state == PairingState.Failed ->
                        Button(onClick = onClose) { Text(stringResource(R.string.action_ok)) }
                    else -> TextButton(onClick = onReject) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun Comparing(state: PairingState.Comparing) {
    val german = LocalConfiguration.current.locales[0].language == "de"
    Text(stringResource(R.string.pairing_compare_title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.pairing_compare_body, state.peerName), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    Countdown(state.deadline)
    Spacer(Modifier.height(24.dp))
    // One row; the pictures are bundled, so both phones show the very same images.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.emojis.forEach { EmojiCell(it, german, Modifier.weight(1f)) }
    }
    if (state.confirmed) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.pairing_waiting_for_peer, state.peerName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A bar that runs empty until the pairing gives up, so the time limit is visible. */
@Composable
private fun Countdown(deadline: Long) {
    fun remaining() = ((deadline - SystemClock.elapsedRealtime()).toFloat() / PairingRunner.TIMEOUT_MS).coerceIn(0f, 1f)
    var fraction by remember(deadline) { mutableFloatStateOf(remaining()) }
    LaunchedEffect(deadline) {
        while (fraction > 0f) {
            delay(COUNTDOWN_TICK_MS)
            fraction = remaining()
        }
    }
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
}

private const val COUNTDOWN_TICK_MS = 250L

@Composable
private fun EmojiCell(emoji: SasEmoji, german: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(SAS_EMOJI_IMAGES[SasEmojis.all.indexOf(emoji)]),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (german) emoji.german else emoji.english,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Noto emoji images (Apache 2.0) in the order of [SasEmojis.all]. */
private val SAS_EMOJI_IMAGES = intArrayOf(
    R.drawable.sas_emoji_00,
    R.drawable.sas_emoji_01,
    R.drawable.sas_emoji_02,
    R.drawable.sas_emoji_03,
    R.drawable.sas_emoji_04,
    R.drawable.sas_emoji_05,
    R.drawable.sas_emoji_06,
    R.drawable.sas_emoji_07,
    R.drawable.sas_emoji_08,
    R.drawable.sas_emoji_09,
    R.drawable.sas_emoji_10,
    R.drawable.sas_emoji_11,
    R.drawable.sas_emoji_12,
    R.drawable.sas_emoji_13,
    R.drawable.sas_emoji_14,
    R.drawable.sas_emoji_15,
    R.drawable.sas_emoji_16,
    R.drawable.sas_emoji_17,
    R.drawable.sas_emoji_18,
    R.drawable.sas_emoji_19,
    R.drawable.sas_emoji_20,
    R.drawable.sas_emoji_21,
    R.drawable.sas_emoji_22,
    R.drawable.sas_emoji_23,
    R.drawable.sas_emoji_24,
    R.drawable.sas_emoji_25,
    R.drawable.sas_emoji_26,
    R.drawable.sas_emoji_27,
    R.drawable.sas_emoji_28,
    R.drawable.sas_emoji_29,
    R.drawable.sas_emoji_30,
    R.drawable.sas_emoji_31,
    R.drawable.sas_emoji_32,
    R.drawable.sas_emoji_33,
    R.drawable.sas_emoji_34,
    R.drawable.sas_emoji_35,
    R.drawable.sas_emoji_36,
    R.drawable.sas_emoji_37,
    R.drawable.sas_emoji_38,
    R.drawable.sas_emoji_39,
    R.drawable.sas_emoji_40,
    R.drawable.sas_emoji_41,
    R.drawable.sas_emoji_42,
    R.drawable.sas_emoji_43,
    R.drawable.sas_emoji_44,
    R.drawable.sas_emoji_45,
    R.drawable.sas_emoji_46,
    R.drawable.sas_emoji_47,
    R.drawable.sas_emoji_48,
    R.drawable.sas_emoji_49,
    R.drawable.sas_emoji_50,
    R.drawable.sas_emoji_51,
    R.drawable.sas_emoji_52,
    R.drawable.sas_emoji_53,
    R.drawable.sas_emoji_54,
    R.drawable.sas_emoji_55,
    R.drawable.sas_emoji_56,
    R.drawable.sas_emoji_57,
    R.drawable.sas_emoji_58,
    R.drawable.sas_emoji_59,
    R.drawable.sas_emoji_60,
    R.drawable.sas_emoji_61,
    R.drawable.sas_emoji_62,
    R.drawable.sas_emoji_63,
)
