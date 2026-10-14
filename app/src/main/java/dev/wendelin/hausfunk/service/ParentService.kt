package dev.wendelin.hausfunk.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.discovery.FoundBaby
import dev.wendelin.hausfunk.media.ParentSession
import dev.wendelin.hausfunk.signaling.Hello
import dev.wendelin.hausfunk.signaling.PROTOCOL_VERSION
import dev.wendelin.hausfunk.ui.common.appVersion
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Parent side in the background: owns the [ParentSession], so sound keeps playing with the screen
 * off, after picture-in-picture is closed and after the app is swiped away. Only "Stop" ends it.
 */
class ParentService : Service() {
    private val app get() = application as HausfunkApplication
    private var session: ParentSession? = null
    private var trustJob: Job? = null

    /** The baby being watched; read by the trust watcher. */
    @Volatile private var babyId: String? = null
    private val locks by lazy { Locks(this, "parent") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        val baby = intent?.let(::babyFrom)
        if (baby == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                Notifications.ID_PARENT,
                Notifications.ongoing(this, ParentService::class.java, getString(R.string.notification_parent, baby.name)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // A restart by the system while the app is in the background: tell the parent loudly
            // instead of silently not monitoring.
            Log.w(TAG, "foreground restart refused")
            getSystemService(NotificationManager::class.java).notify(Notifications.ID_STOPPED, Notifications.stopped(this))
            stopMonitoring()
            return START_NOT_STICKY
        }
        locks.acquire()
        babyId = baby.deviceId
        app.appScope.launch {
            val identity = app.identity.await()
            val s = session ?: ParentSession(this@ParentService, identity, app::currentTrustedIds).also {
                session = it
                app.parentSession.value = it
            }
            val name = app.settings.current().deviceName
            s.connect(baby, Hello(PROTOCOL_VERSION, identity.deviceId, name, appVersion(this@ParentService), watching = true))
            if (trustJob == null) {
                trustJob = app.appScope.launch {
                    // A baby removed in the settings is disconnected and monitoring ends.
                    app.trustedIds.filterNotNull().collect { ids ->
                        if (babyId !in ids) withContext(Dispatchers.Main) { stopMonitoring() }
                    }
                }
            }
        }
        // If the system kills and restarts the service, try to resume watching the same baby.
        return START_REDELIVER_INTENT
    }

    /** Swiping the app away keeps monitoring; only "Stop" ends it. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun stopMonitoring() {
        trustJob?.cancel()
        trustJob = null
        session?.release()
        session = null
        app.parentSession.value = null
        locks.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun babyFrom(intent: Intent): FoundBaby? {
        val id = intent.getStringExtra(EXTRA_ID) ?: return null
        val host = intent.getStringExtra(EXTRA_HOST) ?: return null
        val port = intent.getIntExtra(EXTRA_PORT, -1).takeIf { it in 1..65535 } ?: return null
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        // Numeric address from discovery: no DNS lookup happens here.
        return FoundBaby(id, name, InetAddress.getByName(host), port)
    }

    companion object {
        private const val TAG = "ParentService"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        /** Starts or switches watching [baby]. Call from the visible app. */
        fun watch(context: Context, baby: FoundBaby) {
            val intent = Intent(context, ParentService::class.java)
                .putExtra(EXTRA_ID, baby.deviceId)
                .putExtra(EXTRA_NAME, baby.name)
                .putExtra(EXTRA_HOST, baby.address.hostAddress)
                .putExtra(EXTRA_PORT, baby.port)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ParentService::class.java).setAction(ACTION_STOP))
        }
    }
}
