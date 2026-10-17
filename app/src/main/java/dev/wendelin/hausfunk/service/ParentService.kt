package dev.wendelin.hausfunk.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.wendelin.hausfunk.HausfunkApplication
import dev.wendelin.hausfunk.R
import dev.wendelin.hausfunk.alarm.ConnectionAlarm
import dev.wendelin.hausfunk.data.Role
import dev.wendelin.hausfunk.identity.Identity
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
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    /**
     * Bumped by every start and stop: a start still setting up gives way to a later start or a
     * stop, so it never revives monitoring or watches the wrong baby.
     */
    private var generation = 0

    /** The latest start, so stopping does not swallow one the system queued after it. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        val baby = intent?.let(::babyFrom)
        if (baby == null) {
            if (session == null) stopSelf(startId)
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
        // One role at a time: watching stops this device being a baby.
        if (BabyService.running) BabyService.stop(this)
        locks.acquire()
        watched.value = baby
        val startedIn = ++generation
        // Restarted by the system after it had killed us: watching was already going on, so a
        // baby that cannot be reached counts as a lost connection and the alarm covers it.
        val resume = (flags and START_FLAG_REDELIVERY) != 0
        app.appScope.launch {
            val identity = app.identity.await()
            val settings = app.settings.current()
            val name = settings.deviceName
            withContext(Dispatchers.Main) {
                // Stopped meanwhile ("X" right after tapping, or the service is going away).
                if (startedIn != generation) return@withContext
                // A restart after the process died resumes only on a device that is still a parent:
                // never on one that has become the baby since (it would ring a false alarm).
                if (resume && (settings.lastRole != Role.PARENT || BabyService.running)) {
                    Log.i(TAG, "not resuming: this device is no longer a parent")
                    stopMonitoring()
                    return@withContext
                }
                val s = session ?: createSession(identity)
                val hello = Hello(PROTOCOL_VERSION, identity.deviceId, name, appVersion(this@ParentService))
                s.connect(baby, hello, settings.alarmGraceSeconds * 1_000L, resume)
            }
        }
        // If the system kills and restarts the service, try to resume watching the same baby.
        return START_REDELIVER_INTENT
    }

    private fun createSession(identity: Identity): ParentSession {
        val s = ParentSession(
            this,
            identity,
            app::currentTrustedIds,
            resolve = { id -> finder.babies.value.firstOrNull { it.deviceId == id } },
            bind = app.lanNetworks::bind,
            onUnpaired = { baby -> app.forgetUnpaired(baby.deviceId, baby.name) },
            hasLan = app.lanNetworks::hasLan,
        )
        session = s
        app.parentSession.value = s
        finder.start()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch {
            // A baby removed in the settings is disconnected and monitoring ends.
            app.trustedIds.filterNotNull().collect { ids ->
                if (watched.value?.deviceId !in ids) {
                    s.sendUnpair() // so the baby forgets us too
                    stopMonitoring()
                }
            }
        }
        sc.launch {
            combine(app.settings.settings, watched) { settings, baby -> settings to baby }.collect { (settings, baby) ->
                s.setGracePeriod(settings.alarmGraceSeconds * 1_000L)
                alarm.sound = settings.alarmSound?.toUri()
                if (baby != null) s.setSoundPrefs(settings.soundFor(baby.deviceId))
            }
        }
        sc.launch {
            combine(s.alarm, s.state) { a, st -> a to st }.collect { (a, st) ->
                when {
                    // Never on a device that runs as the baby; monitoring as a parent ends there.
                    a == AlarmState.RINGING && BabyService.running -> stopMonitoring()
                    a == AlarmState.RINGING -> alarm.start((st as? ParentState.WithBaby)?.baby?.name.orEmpty(), s.noLan.value)
                    else -> alarm.stop()
                }
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
        generation++
        scope?.cancel()
        scope = null
        if (session != null) finder.stop() // started with the session
        alarm.stop()
        session?.release()
        session = null
        app.parentSession.value = null
        watched.value = null
        locks.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
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

        /** Whether this device currently runs as a parent (the service exists). Main thread. */
        @Volatile var running = false
            private set
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
