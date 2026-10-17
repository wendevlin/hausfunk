package dev.wendelin.hausfunk.compat

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import java.net.Socket

/**
 * The Wi-Fi and Ethernet networks the phone is on. Android sends app sockets over the default
 * network, which may be mobile data right after rejoining the Wi-Fi (or when the Wi-Fi has no
 * internet); [bind] pins a socket to the local network that reaches the baby instead.
 * [onChange] runs whenever such a network appears or goes, on a system thread.
 */
class LanNetworks(context: Context, private val onChange: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val links = mutableMapOf<Network, LinkProperties>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // A network counts once its routes are known; also when its subnet changes.
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val old = synchronized(links) { links.put(network, linkProperties) }
            if (old == null || old.routes != linkProperties.routes) onChange()
        }

        override fun onLost(network: Network) {
            synchronized(links) { links.remove(network) }
            onChange()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // A home Wi-Fi without internet is still the way to the baby.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
    }

    /** Whether the phone is on any Wi-Fi or Ethernet network at all. */
    fun hasLan(): Boolean = synchronized(links) { links.isNotEmpty() }

    /** The local network whose subnet contains [address]; null if none (then the default is used). */
    fun networkFor(address: InetAddress): Network? = synchronized(links) {
        links.entries.firstOrNull { (_, lp) ->
            lp.routes.any { it.destination.prefixLength > 0 && it.matches(address) }
        }?.key
    }

    /** Sends [socket] (not yet connected) over the local network that reaches [address]. */
    fun bind(socket: Socket, address: InetAddress) {
        networkFor(address)?.bindSocket(socket)
    }
}
