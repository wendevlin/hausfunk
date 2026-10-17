package dev.wendelin.hausfunk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store: DataStore<Settings> = DataStoreFactory.create(
        serializer = JsonSerializer(Settings.serializer(), Settings()),
        // An unreadable file must not stop the app from starting; it starts over instead.
        corruptionHandler = ReplaceFileCorruptionHandler { Settings() },
        scope = scope,
        produceFile = { context.dataStoreFile("settings.json") },
    )

    val settings: Flow<Settings> = store.data

    suspend fun current(): Settings = store.data.first()

    suspend fun setDeviceName(name: String) {
        store.updateData { it.copy(deviceName = DeviceName.sanitize(name).trim()) }
    }

    suspend fun completeOnboarding() {
        store.updateData { it.copy(onboardingComplete = true) }
    }

    suspend fun setLastRole(role: Role) {
        store.updateData { it.copy(lastRole = role) }
    }

    suspend fun setIdentity(deviceId: String, resetNotice: Boolean) {
        store.updateData {
            it.copy(identityDeviceId = deviceId, identityResetNotice = it.identityResetNotice || resetNotice)
        }
    }

    suspend fun dismissIdentityResetNotice() {
        store.updateData { it.copy(identityResetNotice = false) }
    }

    suspend fun setAlarmGrace(seconds: Int) {
        store.updateData { it.copy(alarmGraceSeconds = seconds.coerceIn(Settings.ALARM_GRACE_RANGE)) }
    }

    /** Changes the stored sound settings of [deviceId] with [change], atomically. */
    suspend fun updateSound(deviceId: String, change: (SoundPrefs) -> SoundPrefs) {
        store.updateData { settings ->
            val prefs = change(settings.soundFor(deviceId))
            val clean = prefs.copy(
                thresholdDbfs = prefs.thresholdDbfs.coerceIn(SoundPrefs.THRESHOLD_RANGE),
                holdSeconds = prefs.holdSeconds.coerceIn(SoundPrefs.HOLD_RANGE),
            )
            settings.copy(sound = settings.sound + (deviceId to clean))
        }
    }

    /** Forgets the sound settings of a device that is no longer paired. */
    suspend fun forgetSound(deviceId: String) {
        store.updateData { it.copy(sound = it.sound - deviceId) }
    }
}
