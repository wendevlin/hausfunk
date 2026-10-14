package dev.wendelin.hausfunk.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.wendelin.hausfunk.compat.ServiceResolver
import dev.wendelin.hausfunk.compat.addressesCompat
import dev.wendelin.hausfunk.SerialExecutor
import dev.wendelin.hausfunk.data.DeviceName
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val SERVICE_TYPE = "_babyphone._tcp"
private const val TAG = "Discovery"

/** A baby device seen on the LAN and resolved to an address. */
data class FoundBaby(
    val deviceId: String,
    val name: String,
    val address: InetAddress,
    val port: Int,
    /** The baby's pairing window is open (`pair=1`). */
    val pairable: Boolean,
)

/**
 * Baby side: advertises the signaling port with `id` and `v` in the TXT record, plus `pair=1`
 * while the pairing window is open. Changing that flag registers the service again, so parents on
 * every Android version see the change as the service going and coming back.
 */
class BabyAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start(name: String, deviceId: String, port: Int, pairing: Boolean) {
        check(listener == null) { "already advertising" }
        val info = NsdServiceInfo().apply {
            // The service name is shown to parents; NSD appends " (2)" on a clash.
            serviceName = DeviceName.sanitize(name).ifBlank { "Hausfunk" }
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("v", "1")
            if (pairing) setAttribute("pair", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd.unregisterService(l)
        } catch (e: IllegalArgumentException) {
            // Registration had failed.
        }
    }
}

/**
 * Parent side: discovers `_babyphone._tcp` services and resolves each one, because the TXT
 * attributes (the device ID) are only available after a resolve. All state lives on its own thread.
 */
class BabyFinder(context: Context) {
    private val executor = SerialExecutor("discovery")
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val resolver = ServiceResolver(nsd, executor)
    private val byService = mutableMapOf<String, FoundBaby>()
    private val _babies = MutableStateFlow<List<FoundBaby>>(emptyList())
    val babies: StateFlow<List<FoundBaby>> = _babies.asStateFlow()
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() = executor.execute {
        if (listener != null) return@execute
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                executor.execute { if (listener === this) listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = executor.execute {
                if (listener === this) resolver.start(info, ::onResolved)
            }
            override fun onServiceLost(info: NsdServiceInfo) = executor.execute {
                resolver.stop(info.serviceName)
                if (byService.remove(info.serviceName) != null) publish()
            }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() = executor.execute {
        val l = listener ?: return@execute
        listener = null
        try {
            nsd.stopServiceDiscovery(l)
        } catch (e: IllegalArgumentException) {
            // Discovery had failed to start.
        }
        resolver.stopAll()
        byService.clear()
        publish()
    }

    /** Stops discovery for good. */
    fun release() {
        stop()
        executor.shutdown()
    }

    private fun onResolved(info: NsdServiceInfo) {
        if (listener == null) return
        val id = info.attributes["id"]?.toString(Charsets.UTF_8)
        val version = info.attributes["v"]?.toString(Charsets.UTF_8)
        val address = info.addressesCompat().firstOrNull()
        if (id == null || version != "1" || address == null || !isDeviceId(id)) return
        val pairable = info.attributes["pair"]?.toString(Charsets.UTF_8) == "1"
        byService[info.serviceName] = FoundBaby(id, DeviceName.sanitize(info.serviceName), address, info.port, pairable)
        publish()
    }

    private fun publish() {
        // While a baby re-registers, its old and new service can both be visible; the newer
        // announcement is the pairable one when the window just opened.
        _babies.value = byService.values
            .sortedByDescending { it.pairable }
            .distinctBy { it.deviceId }
            .sortedBy { it.name.lowercase() }
    }

    private fun isDeviceId(id: String) = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }
}
