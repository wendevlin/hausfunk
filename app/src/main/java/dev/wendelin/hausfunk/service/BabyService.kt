package dev.wendelin.hausfunk.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.media.BabySession
import dev.wendelin.hausfunk.ui.common.appVersion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Baby side in the background: owns the [BabySession] (camera, microphone, connections) so that
 * monitoring continues with the screen off and after the app is swiped away. Only "Stop" ends it.
 * Started from the visible baby screen, as Android requires for camera and microphone services.
 */
class BabyService : Service() {
    private val app get() = application as HausfunkApplication
    private var session: BabySession? = null
    private var job: Job? = null
    private val locks by lazy { Locks(this, "baby") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        if (session != null || job != null) return START_NOT_STICKY
        // One role at a time: a device that becomes the baby stops listening as a parent (and
        // can never ring the parents' connection alarm).
        if (ParentService.running) ParentService.stop(this)
        try {
            startForeground(
                Notifications.ID_BABY,
                Notifications.ongoing(this, BabyService::class.java, getString(R.string.notification_baby)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: RuntimeException) {
            // Refused (e.g. not started from the visible screen). Never crash over it.
            Log.w(TAG, "foreground start refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        locks.acquire()
        job = app.appScope.launch {
            val identity = app.identity.await()
            app.trustedIds.filterNotNull().first() // pinning needs the stored pairings
            val name = app.settings.current().deviceName
            val s = BabySession(
                this@BabyService,
                identity,
                app::currentTrustedIds,
                onPaired = { app.storePaired(it, Role.PARENT) },
                onUnpaired = app::forgetUnpaired,
            )
            session = s
            app.babySession.value = s
            s.start(name, appVersion(this@BabyService))
            app.trustedIds.filterNotNull().collect { s.onTrustChanged(it) }
        }
        // A camera service cannot be restarted from the background; the parents' alarm covers it.
        return START_NOT_STICKY
    }

    /** Swiping the app away keeps monitoring; only "Stop" ends it. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onDestroy() {
        running = false
        stopMonitoring()
        super.onDestroy()
    }

    private fun stopMonitoring() {
        job?.cancel()
        job = null
        session?.stop()
        session = null
        app.babySession.value = null
        locks.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "BabyService"

        /** Whether this device currently runs as the baby (the service exists). Main thread. */
        @Volatile var running = false
            private set

        /** Must be called while the baby screen is visible. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, BabyService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BabyService::class.java).setAction(ACTION_STOP))
        }
    }
}
