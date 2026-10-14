package io.github.wendevlin.hausfunk.compat

import android.content.Context
import android.os.Build
import android.provider.Settings

object SystemDeviceName {
    /** The name the user gave the phone in system settings, or the model as a fallback. */
    fun suggestion(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
}
