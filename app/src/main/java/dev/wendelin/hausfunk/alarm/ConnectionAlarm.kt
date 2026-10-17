package dev.wendelin.hausfunk.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import dev.wendelin.hausfunk.compat.AlarmCompat
import dev.wendelin.hausfunk.service.Notifications

/**
 * Parent side: the loud, persistent alarm when the baby device is unreachable. Plays the phone's
 * alarm sound on the alarm stream in a loop, vibrates and shows a full-screen notification until
 * [stop]. Main thread only.
 */
class ConnectionAlarm(private val context: Context) {
    private var active = false
    private var ringtone: Ringtone? = null
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** The sound chosen in the settings; null (or one that is gone) plays the phone's alarm sound. */
    var sound: Uri? = null

    /** [noLan]: this phone has no Wi-Fi, so the notification names that instead of the baby. */
    fun start(babyName: String, noLan: Boolean) {
        notifications.notify(Notifications.ID_ALARM, Notifications.alarm(context, babyName, noLan))
        if (active) return
        active = true
        ringtone = ringtone()?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        if (ringtone == null) Log.w(TAG, "no alarm sound available, vibrating only")
        AlarmCompat.vibrateAsAlarm(vibrator, VibrationEffect.createWaveform(PATTERN, 0))
    }

    fun stop() {
        if (!active) return
        active = false
        ringtone?.stop()
        ringtone = null
        vibrator.cancel()
        notifications.cancel(Notifications.ID_ALARM)
    }

    /**
     * The chosen sound if it can still be read (it may have been deleted), else the phone's alarm
     * sound (or any sound the phone has).
     */
    private fun ringtone(): Ringtone? = (
        listOfNotNull(sound?.takeIf(::readable)) + listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION,
        ).mapNotNull { RingtoneManager.getDefaultUri(it) }
    ).firstNotNullOfOrNull { RingtoneManager.getRingtone(context, it) }

    private fun readable(uri: Uri): Boolean =
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } }.getOrNull() == true

    private companion object {
        const val TAG = "ConnectionAlarm"
        val PATTERN = longArrayOf(0, 800, 600)
    }
}
