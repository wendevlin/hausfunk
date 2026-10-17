package dev.wendelin.hausfunk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TrustStoreRepository(context: Context, scope: CoroutineScope) {
    private val store: DataStore<TrustStore> = DataStoreFactory.create(
        serializer = JsonSerializer(TrustStore.serializer(), TrustStore()),
        // An unreadable file must not stop the app from starting; empty trusts nobody.
        corruptionHandler = ReplaceFileCorruptionHandler { TrustStore() },
        scope = scope,
        produceFile = { context.dataStoreFile("trust_store.json") },
    )

    val devices: Flow<List<TrustedDevice>> = store.data.map { it.devices }

    /** Adds a freshly paired device. A device that is already present is left as it is. */
    suspend fun add(device: TrustedDevice) {
        store.updateData { trust ->
            if (trust.devices.any { it.deviceId == device.deviceId }) trust else trust.copy(devices = trust.devices + device)
        }
    }

    suspend fun rename(deviceId: String, name: String) {
        val clean = DeviceName.sanitize(name).trim()
        if (clean.isEmpty()) return
        store.updateData { trust ->
            trust.copy(devices = trust.devices.map { if (it.deviceId == deviceId) it.copy(name = clean) else it })
        }
    }

    suspend fun remove(deviceId: String) {
        store.updateData { trust -> trust.copy(devices = trust.devices.filterNot { it.deviceId == deviceId }) }
    }

    suspend fun clear() {
        store.updateData { TrustStore() }
    }
}
