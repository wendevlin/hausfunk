package dev.wendelin.hausfunk.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.pairing.PairingState
import dev.wendelin.hausfunk.pairing.SasEmoji

/**
 * The pairing exchange as both phones show it: the 7 emojis with their names, "They match" and
 * "Don't match"; then the result. [onClose] dismisses a finished exchange.
 */
@Composable
fun PairingContent(state: PairingState, onConfirm: () -> Unit, onReject: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Comparing(state: PairingState.Comparing) {
    val german = LocalConfiguration.current.locales[0].language == "de"
    Text(stringResource(R.string.pairing_compare_title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.pairing_compare_body, state.peerName), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.emojis.forEach { EmojiCell(it, german) }
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

@Composable
private fun EmojiCell(emoji: SasEmoji, german: Boolean) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji.emoji, fontSize = 40.sp)
        Text(
            if (german) emoji.german else emoji.english,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}
