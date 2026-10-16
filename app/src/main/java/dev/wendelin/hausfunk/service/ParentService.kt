package dev.wendelin.hausfunk.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.alarm.ConnectionAlarm
import dev.wendelin.hausfunk.compat.nsdReportsAddressChanges
import dev.wendelin.hausfunk.discovery.FoundBaby
import dev.wendelin.hausfunk.media.AlarmState
import dev.wendelin.hausfunk.media.BabyStatus
import dev.wendelin.hausfunk.media.ParentSession
import dev.wendelin.hausfunk.media.ParentState
import dev.wendelin.hausfunk.signaling.Hello
import dev.wendelin.hausfunk.signaling.PROTOCOL_VERSION
import dev.wendelin.hausfunk.ui.common.appVersion
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parent side in the background: owns the [ParentSession], so sound keeps playing with the screen
 * off, after picture-in-picture is closed and after the app is swiped away. Only "Stop" ends it.
 * Also rings the connection-loss alarm and warns about the baby device's battery.
 */
class ParentService : Service() {
    private val app get() = application as HausfunkApplication
    private var session: ParentSession? = null

    /** Everything that follows the session; cancelled with it. */
    private var scope: CoroutineScope? = null

    /** The baby being watched. */
    private val watched = MutableStateFlow<FoundBaby?>(null)
    private val locks by lazy { Locks(this, "parent") }
    private val alarm by lazy { ConnectionAlarm(this) }
    private val finder get() = app.babyFinder
    private var finding = false
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

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
                Notifications.ongoing(this, ParentService::class.java, getString(R.string.watch_connecting, baby.name)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // A restart by the system while the app is in the background: tell the parent loudly
            // instead of silently not monitoring.
            Log.w(TAG, "foreground restart refused")
            notifications.notify(Notifications.ID_STOPPED, Notifications.stopped(this))
            stopMonitoring()
            return START_NOT_STICKY
        }
        locks.acquire()
        watched.value = baby
        // Restarted by the system after it had killed us: watching was already going on, so a
        // baby that cannot be reached counts as a lost connection and the alarm covers it.
        val resume = (flags and START_FLAG_REDELIVERY) != 0
        app.appScope.launch {
            val identity = app.identity.await()
            val name = app.settings.current().deviceName
            withContext(Dispatchers.Main) {
                val s = session ?: createSession(identity)
                s.connect(baby, Hello(PROTOCOL_VERSION, identity.deviceId, name, appVersion(this@ParentService), watching = true), resume)
            }
        }
        // If the system kills and restarts the service, try to resume watching the same baby.
        return START_REDELIVER_INTENT
    }

    private fun createSession(identity: dev.wendelin.hausfunk.identity.Identity): ParentSession {
        val s = ParentSession(
            this,
            identity,
            app::currentTrustedIds,
            resolve = { id -> finder.babies.value.firstOrNull { it.deviceId == id } },
            bind = app.lanNetworks::bind,
            onUnpaired = { baby -> app.forgetUnpaired(baby.deviceId, baby.name) },
        )
        session = s
        app.parentSession.value = s
        finder.start()
        finding = true
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch {
            // A baby removed in the settings is disconnected and monitoring ends.
            app.trustedIds.filterNotNull().collect { ids ->
                s.onTrustChanged(ids) // tells the baby, if it was removed here
                if (watched.value?.deviceId !in ids) stopMonitoring()
            }
        }
        sc.launch {
            combine(app.settings.settings, watched) { settings, baby -> settings to baby }.collect { (settings, baby) ->
                s.setGracePeriod(settings.alarmGraceSeconds * 1_000L)
                if (baby != null) s.setSoundPrefs(settings.soundFor(baby.deviceId))
            }
        }
        sc.launch {
            combine(s.alarm, s.state) { a, st -> a to st }.collect { (a, st) ->
                if (a == AlarmState.RINGING) alarm.start((st as? ParentState.WithBaby)?.baby?.name.orEmpty()) else alarm.stop()
            }
        }
        sc.launch {
            // The ongoing notification follows the state; the last sound counts in 30 s steps,
            // the notification time shows the exact age.
            combine(s.state, s.lastSound.map { it?.div(30_000) }.distinctUntilChanged()) { st, _ -> st }
                .collect { st -> updateNotification(st, s.lastSound.value) }
        }
        sc.launch { warnAboutBattery(s) }
        if (!nsdReportsAddressChanges) {
            sc.launch {
                s.state.map { it is ParentState.Reconnecting }.distinctUntilChanged().collectLatest { lost ->
                    while (lost) {
                        finder.refresh()
                        delay(REDISCOVER_MS)
                    }
                }
            }
        }
        return s
    }

    private fun updateNotification(state: ParentState, lastSound: Long?) {
        val text = when (state) {
            is ParentState.Connected -> getString(R.string.notification_parent, state.baby.name)
            is ParentState.Reconnecting -> getString(R.string.notification_reconnecting, state.baby.name)
            is ParentState.Busy -> getString(R.string.watch_busy, state.baby.name)
            is ParentState.Connecting -> getString(R.string.watch_connecting, state.baby.name)
            ParentState.Idle -> return
        }
        val wallClock = lastSound?.let { System.currentTimeMillis() - (SystemClock.elapsedRealtime() - it) }
        notifications.notify(Notifications.ID_PARENT, Notifications.ongoing(this, ParentService::class.java, text, wallClock))
    }

    /** Below 20 % and on unplugging, once each, while the baby is not charging. */
    private suspend fun warnAboutBattery(s: ParentSession) {
        var previous: BabyStatus? = null
        var previousBaby: String? = null
        s.status.filterNotNull().collect { status ->
            val baby = watched.value ?: return@collect
            if (baby.deviceId != previousBaby) previous = null
            previousBaby = baby.deviceId
            val prev = previous
            previous = status
            val text = when {
                prev?.charging == true && !status.charging -> getString(R.string.battery_unplugged, baby.name)
                status.battery in 0 until LOW_BATTERY && !status.charging &&
                    (prev == null || prev.charging || prev.battery >= LOW_BATTERY) ->
                    getString(R.string.battery_low, baby.name, status.battery)
                else -> return@collect
            }
            notifications.notify(Notifications.ID_BATTERY, Notifications.battery(this, text))
        }
    }

    /** Swiping the app away keeps monitoring; only "Stop" ends it. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun stopMonitoring() {
        scope?.cancel()
        scope = null
        if (finding) finder.stop()
        finding = false
        alarm.stop()
        session?.release()
        session = null
        app.parentSession.value = null
        watched.value = null
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
        private const val LOW_BATTERY = 20
        private const val REDISCOVER_MS = 30_000L

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
