package dev.wendelin.hausfunk

import android.app.Application
import dev.wendelin.hausfunk.data.SettingsRepository
import dev.wendelin.hausfunk.data.TrustStoreRepository
import dev.wendelin.hausfunk.compat.LanNetworks
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first

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

    /**
     * Wi-Fi and Ethernet networks. When one appears or goes, discovery starts over and the parent
     * session reconnects at once over the new network.
     */
    val lanNetworks: LanNetworks by lazy {
        LanNetworks(this) {
            babyFinder.refresh()
            parentSession.value?.onNetworkChanged()
        }
    }

    /** The running parent session, owned by ParentService; null while not watching. */
    val parentSession = MutableStateFlow<ParentSession?>(null)

    /** Device IDs in the trust store; null until it has been read once. */
    lateinit var trustedIds: StateFlow<Set<String>?>
        private set

    /**
     * Just paired, not yet in [trustedIds]: trusted at once, so the first connection right after
     * pairing does not race the write to the trust store.
     */
    private val justPaired: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** For TLS handshakes, which need a plain answer right now. */
    fun currentTrustedIds(): Set<String> = (trustedIds.value ?: emptySet()) + justPaired

    /** Stores a peer after both phones confirmed the emojis; trusted from this moment on. */
    fun storePaired(peer: PairedPeer, role: Role) {
        justPaired += peer.deviceId
        val device = TrustedDevice(
            deviceId = peer.deviceId,
            name = peer.name,
            certificate = Base64.getEncoder().encodeToString(peer.certificate),
            role = role,
            pairedAt = System.currentTimeMillis(),
        )
        appScope.launch {
            trustStore.add(device)
            trustedIds.first { it != null && peer.deviceId in it }
            justPaired -= peer.deviceId
        }
    }

    /**
     * Name of a device that removed the pairing on its side, for a one-time notice; null when
     * there is nothing to tell.
     */
    val unpairedNotice = MutableStateFlow<String?>(null)

    /** The other device removed the pairing: forget it here too, and say so once. */
    fun forgetUnpaired(deviceId: String, name: String) {
        appScope.launch {
            trustStore.remove(deviceId)
            settings.forgetSound(deviceId)
        }
        unpairedNotice.value = name
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
        lanNetworks.start()
        settings = SettingsRepository(this, appScope)
        trustStore = TrustStoreRepository(this, appScope)
        trustedIds = trustStore.devices
            .map { devices -> devices.map { it.deviceId }.toSet() }
            .stateIn(appScope, SharingStarted.Eagerly, null)
        val identityManager = IdentityManager(settings, trustStore)
        identity = appScope.async(Dispatchers.IO) { identityManager.ensureIdentity() }
    }
}
