package dev.wendelin.hausfunk.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Keeps the CPU and Wi-Fi awake while monitoring. Not relied on: some phones throttle anyway,
 * which is what the connection-loss alarm (phase 5) is for.
 */
class Locks(context: Context, tag: String) {
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hausfunk:$tag")
        .apply { setReferenceCounted(false) }

    // Deprecated but still honoured; the platform maps it to the closest behaviour.
    @Suppress("DEPRECATION")
    private val wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hausfunk:$tag")
        .apply { setReferenceCounted(false) }

    // Held for as long as monitoring runs; released by release() when it ends.
    @android.annotation.SuppressLint("WakelockTimeout")
    fun acquire() {
        wakeLock.acquire()
        wifiLock.acquire()
    }

    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
    }
}
