package dev.wendelin.hausfunk.ui.parent

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.data.Settings
import dev.wendelin.hausfunk.data.TrustedDevice
import dev.wendelin.hausfunk.discovery.FoundBaby
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.media.ParentState
import dev.wendelin.hausfunk.pairing.ParentPairing
import dev.wendelin.hausfunk.service.ParentService
import dev.wendelin.hausfunk.ui.common.AutoPictureInPicture
import dev.wendelin.hausfunk.ui.common.Zoom
import dev.wendelin.hausfunk.ui.common.rememberInPictureInPicture
import dev.wendelin.hausfunk.ui.common.rememberMissingPermissions
import dev.wendelin.hausfunk.ui.pairing.PairingContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The parent role: the list of baby devices, the watch screen (also in picture-in-picture) and
 * pairing. Watching itself runs in [ParentService], so sound continues with the screen off and
 * in picture-in-picture. One baby at a time, one screen at a time, on phones and tablets alike.
 */
@Composable
fun ParentRoute(
    app: HausfunkApplication,
    settings: Settings,
    trusted: List<TrustedDevice>,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val missing = rememberMissingPermissions(baby = false)
    val identity by produceState<Identity?>(null) { value = app.identity.await() }
    val finder = app.babyFinder
    if (missing.isEmpty()) {
        DisposableEffect(Unit) {
            finder.start()
            onDispose { finder.stop() }
        }
    }
    val found by finder.babies.collectAsState()
    val session by app.parentSession.collectAsState()
    val state by remember(session) { session?.state ?: MutableStateFlow(ParentState.Idle) }.collectAsState()
    var pairing by remember { mutableStateOf<ParentPairing?>(null) }

    // Click handlers read the current values when tapped, never ones captured earlier.
    fun watch(deviceId: String) {
        // The list is only shown while nothing is being watched, so a tap always starts watching.
        val baby = finder.babies.value.firstOrNull { it.deviceId == deviceId } ?: return
        ParentService.watch(context, baby)
    }
    fun stopWatching() = ParentService.stop(context)
    fun pair(baby: FoundBaby) {
        val id = identity ?: return
        val p = ParentPairing(id, settings.deviceName, app::currentTrustedIds, app.lanNetworks::bind) { app.storePaired(it, Role.BABY) }
        pairing = p
        p.start(baby, app.appScope)
    }
    fun endPairing() {
        pairing?.cancel()
        pairing = null
    }

    val p = pairing
    if (p != null) {
        // A pairing whose screen goes away (e.g. the activity is recreated) ends cleanly.
        DisposableEffect(p) { onDispose { p.cancel() } }
        val pairingState by p.state.collectAsState()
        BackHandler { endPairing() }
        Surface(Modifier.fillMaxSize()) {
            PairingContent(pairingState, onConfirm = p::confirm, onReject = ::endPairing, onClose = ::endPairing)
        }
        return
    }

    val s = session
    val watchedId = state.babyId
    if (s != null && watchedId != null) {
        var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
        var videoBounds by remember { mutableStateOf<Rect?>(null) }
        // Shared by the watch screen and picture-in-picture, so the zoom stays when the app is left.
        var zoom by remember(watchedId) { mutableStateOf(Zoom.NONE) }
        val inPip = rememberInPictureInPicture()
        // While zoomed the shown part has another shape than the PiP window, so no source hint.
        AutoPictureInPicture(
            enabled = state is ParentState.Connected,
            aspectRatio = aspectRatio,
            sourceRect = videoBounds.takeIf { !zoom.zoomed },
        )
        WatchScreen(
            s,
            state,
            inPip = inPip,
            soundPrefs = settings.soundFor(watchedId),
            onSoundPrefs = { change -> app.appScope.launch { app.settings.updateSound(watchedId, change) } },
            zoom = zoom,
            onZoom = { zoom = it },
            onClose = ::stopWatching,
            onAspectRatio = { aspectRatio = it },
            onVideoBounds = { videoBounds = it },
        )
        return
    }
    val trustedIds = trusted.map { it.deviceId }.toSet()
    ParentHomeScreen(
        paired = trusted,
        // Pairing is mutual and independent of roles: any paired device may be the baby today.
        newBabies = found.filter { it.deviceId !in trustedIds },
        onlineIds = found.map { it.deviceId }.toSet(),
        missing = missing,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onSelect = ::watch,
        onPair = ::pair,
    )
}
