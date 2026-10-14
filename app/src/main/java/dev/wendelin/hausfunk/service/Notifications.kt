package dev.wendelin.hausfunk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.alarm.AcknowledgeReceiver
import dev.wendelin.hausfunk.compat.AlarmCompat
import dev.wendelin.hausfunk.ui.MainActivity

/** Notification channels and the notifications of the two monitoring services. */
object Notifications {
    const val CHANNEL_MONITORING = "monitoring"
    const val CHANNEL_STOPPED = "stopped"
    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_WARNINGS = "warnings"
    const val ID_BABY = 1
    const val ID_PARENT = 2
    const val ID_STOPPED = 3
    const val ID_ALARM = 4
    const val ID_BATTERY = 5

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING,
                context.getString(R.string.channel_monitoring),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STOPPED,
                context.getString(R.string.channel_stopped),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                context.getString(R.string.channel_alarm),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                // The alarm plays its own looping sound and vibration.
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WARNINGS,
                context.getString(R.string.channel_warnings),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /**
     * The ongoing notification of a running service, with a "Stop" action. [lastSoundAt] (wall
     * clock) shows as the notification time, which the system keeps current ("4 min").
     */
    fun ongoing(
        context: Context,
        service: Class<out Service>,
        text: String,
        lastSoundAt: Long? = null,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .apply {
                if (lastSoundAt != null) {
                    setSubText(context.getString(R.string.notification_last_sound))
                    setWhen(lastSoundAt)
                    setShowWhen(true)
                } else {
                    setShowWhen(false)
                }
            }
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_stop), stopIntent(context, service))
            .build()

    /** Monitoring could not continue on its own (e.g. a restart in the background was refused). */
    fun stopped(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_STOPPED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_stopped_title))
            .setContentText(context.getString(R.string.notification_stopped_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    /** The connection-loss alarm: full screen where allowed, with "Acknowledge". */
    fun alarm(context: Context, babyName: String): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alarm_title, babyName))
            .setContentText(context.getString(R.string.alarm_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(
                0,
                context.getString(R.string.alarm_acknowledge),
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, AcknowledgeReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        if (AlarmCompat.canUseFullScreenIntent(context)) builder.setFullScreenIntent(openApp(context), true)
        return builder.build()
    }

    /** The baby device's battery is low or it was unplugged. */
    fun battery(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(context: Context, service: Class<out Service>): PendingIntent = PendingIntent.getService(
        context,
        0,
        Intent(context, service).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

const val ACTION_STOP = "dev.wendelin.hausfunk.action.STOP"
