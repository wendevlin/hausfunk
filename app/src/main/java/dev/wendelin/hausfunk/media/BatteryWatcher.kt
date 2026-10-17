package dev.wendelin.hausfunk.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/** Battery charge in percent (-1 if unknown) and whether the phone is plugged in. */
data class Battery(val percent: Int, val charging: Boolean)

/** Baby side: reports the battery whenever the percentage or the plug state changes. */
class BatteryWatcher(private val context: Context, private val onChange: (Battery) -> Unit) {
    private var last: Battery? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update(intent)
    }

    /** The current state, from the sticky broadcast. */
    @Synchronized
    fun current(): Battery? = last

    fun start() {
        // The battery broadcast is sticky: registering returns the current state right away.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let(::update)
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Never started.
        }
    }

    @Synchronized
    private fun update(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val battery = Battery(percent, charging)
        if (battery == last) return
        last = battery
        onChange(battery)
    }
}
