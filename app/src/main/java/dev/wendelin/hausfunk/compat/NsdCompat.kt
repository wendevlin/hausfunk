package dev.wendelin.hausfunk.compat

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor

/**
 * Resolves services found by NsdManager discovery. Android 14+ keeps one callback per service and
 * reports address changes. Android 12 and 13 can resolve only one service at a time, so requests
 * are queued there. Every method must be called on [executor]; listeners are called on it too.
 */
class ServiceResolver(private val nsd: NsdManager, private val executor: Executor) {
    private val impl: Impl =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CallbackImpl() else QueueImpl()

    fun start(service: NsdServiceInfo, listener: (NsdServiceInfo) -> Unit) = impl.start(service, listener)

    fun stop(serviceName: String) = impl.stop(serviceName)

    fun stopAll() = impl.stopAll()

    private interface Impl {
        fun start(service: NsdServiceInfo, listener: (NsdServiceInfo) -> Unit)
        fun stop(serviceName: String)
        fun stopAll()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private inner class CallbackImpl : Impl {
        private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

        override fun start(service: NsdServiceInfo, listener: (NsdServiceInfo) -> Unit) {
            if (service.serviceName in callbacks) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    executor.execute { callbacks.remove(service.serviceName) }
                }
                override fun onServiceUpdated(info: NsdServiceInfo) = listener(info)
                override fun onServiceLost() = Unit // discovery reports the loss
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks[service.serviceName] = callback
            nsd.registerServiceInfoCallback(service, executor, callback)
        }

        override fun stop(serviceName: String) {
            val callback = callbacks.remove(serviceName) ?: return
            unregister(callback)
        }

        override fun stopAll() {
            callbacks.values.forEach(::unregister)
            callbacks.clear()
        }

        private fun unregister(callback: NsdManager.ServiceInfoCallback) {
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (e: IllegalArgumentException) {
                // Registration had failed or already ended.
            }
        }
    }

    @Suppress("DEPRECATION") // resolveService is the only option before Android 14.
    private inner class QueueImpl : Impl {
        private val queue = ArrayDeque<Pair<NsdServiceInfo, (NsdServiceInfo) -> Unit>>()
        private var active: String? = null

        override fun start(service: NsdServiceInfo, listener: (NsdServiceInfo) -> Unit) {
            if (active == service.serviceName || queue.any { it.first.serviceName == service.serviceName }) return
            queue.addLast(service to listener)
            next()
        }

        override fun stop(serviceName: String) {
            queue.removeAll { it.first.serviceName == serviceName }
        }

        override fun stopAll() = queue.clear()

        private fun next() {
            if (active != null) return
            val (service, listener) = queue.removeFirstOrNull() ?: return
            active = service.serviceName
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = executor.execute { done() }
                override fun onServiceResolved(info: NsdServiceInfo) = executor.execute {
                    listener(info)
                    done()
                }
            })
        }

        private fun done() {
            active = null
            next()
        }
    }
}

/** The resolved addresses of a service, IPv4 first. */
fun NsdServiceInfo.addressesCompat(): List<InetAddress> {
    val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        hostAddresses
    } else {
        @Suppress("DEPRECATION")
        listOfNotNull(host)
    }
    return all.sortedBy { if (it is Inet4Address) 0 else 1 }
}
