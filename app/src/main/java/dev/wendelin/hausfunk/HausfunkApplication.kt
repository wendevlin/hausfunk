package dev.wendelin.hausfunk

import android.app.Application
import dev.wendelin.hausfunk.data.SettingsRepository
import dev.wendelin.hausfunk.data.TrustStoreRepository
import dev.wendelin.hausfunk.identity.Identity
import dev.wendelin.hausfunk.identity.IdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Holds the app-wide singletons. No DI framework: everything is wired here by hand. */
class HausfunkApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository
        private set
    lateinit var trustStore: TrustStoreRepository
        private set

    /** Created or loaded once per process, off the main thread. */
    lateinit var identity: Deferred<Identity>
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        trustStore = TrustStoreRepository(this, appScope)
        val identityManager = IdentityManager(settings, trustStore)
        identity = appScope.async(Dispatchers.IO) { identityManager.ensureIdentity() }
    }
}
