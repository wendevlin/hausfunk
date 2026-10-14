package dev.wendelin.hausfunk.compat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Version differences for permissions and power settings live here, nowhere else. */
object Permissions {
    /** POST_NOTIFICATIONS exists from Android 13; on 12 notifications are allowed by default. */
    val notificationPermissionNeeded: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Only requested when notificationPermissionNeeded is true.
    @SuppressLint("InlinedApi")
    const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    // A baby monitor is the textbook case for this exemption: some OEMs kill foreground
    // services without it. The app is distributed outside Google Play.
    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
}
