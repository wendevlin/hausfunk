package dev.wendelin.hausfunk

import android.app.Application
import dev.wendelin.hausfunk.data.SettingsRepository
import dev.wendelin.hausfunk.data.TrustStoreRepository
import dev.wendelin.hausfunk.discovery.BabyFinder
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.identity.IdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import dev.wendelin.hausfunk.media.BabySession
import dev.wendelin.hausfunk.media.ParentSession
import dev.wendelin.hausfunk.service.Notifications
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.data.TrustedDevice
import dev.wendelin.hausfunk.pairing.PairedPeer
import java.util.Base64

/** Holds the app-wide singletons. No DI framework: everything is wired here by hand. */
class HausfunkApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository
        private set
    lateinit var trustStore: TrustStoreRepository
        private set

    /** Created or loaded once per process, off the main thread. */
    lateinit var identity: Deferred<Identity>
        private set

    /** The running baby session, owned by BabyService; null while not monitoring as baby. */
    val babySession = MutableStateFlow<BabySession?>(null)

    /** Finds baby devices on the Wi-Fi; shared by the parent screens and ParentService. */
    val babyFinder by lazy { BabyFinder(this) }

    /** The running parent session, owned by ParentService; null while not watching. */
    val parentSession = MutableStateFlow<ParentSession?>(null)

    /** Device IDs in the trust store; null until it has been read once. */
    lateinit var trustedIds: StateFlow<Set<String>?>
        private set

    /** For TLS handshakes, which need a plain answer right now. */
    fun currentTrustedIds(): Set<String> = trustedIds.value ?: emptySet()

    /** Stores a peer after both phones confirmed the emojis. */
    fun storePaired(peer: PairedPeer, role: Role) {
        val device = TrustedDevice(
            deviceId = peer.deviceId,
            name = peer.name,
            certificate = Base64.getEncoder().encodeToString(peer.certificate),
            role = role,
            pairedAt = System.currentTimeMillis(),
        )
        appScope.launch { trustStore.add(device) }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        settings = SettingsRepository(this, appScope)
        trustStore = TrustStoreRepository(this, appScope)
        trustedIds = trustStore.devices
            .map { devices -> devices.map { it.deviceId }.toSet() }
            .stateIn(appScope, SharingStarted.Eagerly, null)
        val identityManager = IdentityManager(settings, trustStore)
        identity = appScope.async(Dispatchers.IO) { identityManager.ensureIdentity() }
    }
}
