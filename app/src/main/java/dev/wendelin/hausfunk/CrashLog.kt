package dev.wendelin.hausfunk

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Debug builds only: keeps the stack trace of the last crash in private storage, so the next
 * start can show it and the tester can copy it. Release builds record nothing.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file.writeText("Thread: ${thread.name}\n$trace")
            } catch (e: Exception) {
                // Nothing more to do while crashing.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, if one was recorded and not yet dismissed. */
    fun last(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
