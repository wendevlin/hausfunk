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
    private const val ANDROID_17 = 37

    /** POST_NOTIFICATIONS exists from Android 13; on 12 notifications are allowed by default. */
    val notificationPermissionNeeded: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Only requested when notificationPermissionNeeded is true.
    @SuppressLint("InlinedApi")
    const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    /**
     * Android 17 made local network access a runtime permission for apps targeting it; without
     * it NsdManager silently finds nothing. Earlier versions grant it with INTERNET.
     */
    val localNetworkPermissionNeeded: Boolean
        get() = Build.VERSION.SDK_INT >= ANDROID_17

    // Only requested when localNetworkPermissionNeeded is true.
    @SuppressLint("InlinedApi")
    const val LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** Permissions the current role cannot work without, that are not granted yet. */
    fun missingForRole(context: Context, baby: Boolean): List<String> = buildList {
        if (baby) {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (localNetworkPermissionNeeded) add(LOCAL_NETWORK)
    }.filterNot { isGranted(context, it) }

    /** The app's page in the system settings, for permissions the user denied for good. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

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
