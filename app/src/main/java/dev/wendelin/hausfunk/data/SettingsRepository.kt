package dev.wendelin.hausfunk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store: DataStore<Settings> = DataStoreFactory.create(
        serializer = JsonSerializer(Settings.serializer(), Settings()),
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

    suspend fun setSound(deviceId: String, prefs: SoundPrefs) {
        val clean = prefs.copy(
            thresholdDbfs = prefs.thresholdDbfs.coerceIn(SoundPrefs.THRESHOLD_RANGE),
            holdSeconds = prefs.holdSeconds.coerceIn(SoundPrefs.HOLD_RANGE),
        )
        store.updateData { it.copy(sound = it.sound + (deviceId to clean)) }
    }

    /** Forgets the sound settings of a device that is no longer paired. */
    suspend fun forgetSound(deviceId: String) {
        store.updateData { it.copy(sound = it.sound - deviceId) }
    }
}
