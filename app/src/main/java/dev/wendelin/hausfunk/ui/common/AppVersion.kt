package dev.wendelin.hausfunk.ui.common

import android.content.Context

/** versionName for the `hello` message (BuildConfig is turned off). */
fun appVersion(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
