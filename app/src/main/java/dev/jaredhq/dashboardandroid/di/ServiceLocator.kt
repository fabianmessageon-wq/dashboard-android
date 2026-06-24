package dev.jaredhq.dashboardandroid.di

import android.content.Context
import androidx.room.Room
import dev.jaredhq.dashboardandroid.BuildConfig
import dev.jaredhq.dashboardandroid.data.api.ApiClientFactory
import dev.jaredhq.dashboardandroid.data.api.DashboardApiClient
import dev.jaredhq.dashboardandroid.data.api.FakeDashboardApiClient
import dev.jaredhq.dashboardandroid.data.cache.TodayCache
import dev.jaredhq.dashboardandroid.data.cache.room.CacheDatabase
import dev.jaredhq.dashboardandroid.data.cache.room.RoomTodayCache
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.data.settings.SecureSettingsStore
import dev.jaredhq.dashboardandroid.data.settings.SettingsStore
import dev.jaredhq.dashboardandroid.ble.WatchBleManager
import dev.jaredhq.dashboardandroid.watch.engine.IdoSdkWatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.UploadingWatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.work.WatchSyncScheduler
import androidx.glance.appwidget.updateAll
import dev.jaredhq.dashboardandroid.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal hand-rolled DI. Deliberately not Hilt: the graph is tiny (settings,
 * cache, one repository) and a service locator keeps the build fast and the
 * wiring obvious. Initialized once from [DashboardApp.onCreate].
 *
 * The live API client is rebuilt per repository call from current settings, so a
 * base-URL change in Settings takes effect immediately. When no base URL is
 * configured it falls back to [FakeDashboardApiClient] so the app is fully
 * navigable (with sample data) before any setup.
 */
object ServiceLocator {

    @Volatile
    private var initialized = false

    /** App-lifetime scope for fire-and-forget watch health uploads (off the SDK callback thread). */
    private val watchUploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The single bound watch (Active 4 Pro) MAC — used as the dashboard device id for now. */
    private const val WATCH_DEVICE_ID = "F4:91:29:51:C6:45"

    lateinit var settings: SettingsStore
        private set

    lateinit var cache: TodayCache
        private set

    lateinit var repository: DashboardRepository
        private set

    lateinit var watchBleManager: WatchBleManager
        private set

    /**
     * The active watch data engine (ADR 0001). Currently the vendored-SDK engine
     * ([IdoSdkWatchEngine]); a clean-room engine can be swapped in here behind the same
     * [WatchEngine] interface without touching callers.
     */
    lateinit var watchEngine: WatchEngine
        private set

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            this.appContext = appContext

            settings = SecureSettingsStore(appContext)

            val db = Room.databaseBuilder(
                appContext,
                CacheDatabase::class.java,
                "dashboard-cache.db",
            ).fallbackToDestructiveMigration().build()
            cache = RoomTodayCache(db.todayDao())

            repository = DashboardRepository(
                cache = cache,
                apiProvider = { makeClient() },
            )

            watchBleManager = WatchBleManager(appContext).apply {
                // Auto-upload telemetry whenever the connection state changes
                // (connect/disconnect/error) — the worker no-ops if unconfigured.
                onConnectionEvent = { WatchSyncScheduler.syncNow(appContext) }
            }

            // Vendored-SDK engine (ADR 0001). init() is idempotent; it loads the native lib
            // + opens the SDK's own DB once at startup. Guarded so a native-load/SDK-init
            // failure degrades the watch feature rather than crashing app startup.
            val application = appContext as? android.app.Application
                ?: throw IllegalStateException("ServiceLocator must be initialised with the Application context")
            watchEngine = IdoSdkWatchEngine(application).also {
                // Decoded health records flow to the dashboard via this listener (W5). It buffers
                // per sync and uploads one idempotent batch on completion.
                // TODO: source deviceId from the connected device once the Watch UI drives
                //   connect (today the single bound Active 4 Pro MAC is the only device).
                it.listener = UploadingWatchHealthListener(
                    repository = repository,
                    scope = watchUploadScope,
                    deviceId = WATCH_DEVICE_ID,
                )
                runCatching { it.init() }.onFailure { e ->
                    android.util.Log.e("ServiceLocator", "Watch engine init failed", e)
                }
            }

            initialized = true
        }
    }

    /**
     * Build the client appropriate to current config. No base URL ⇒ fake client
     * (offline previews / pre-setup). The token is read lazily by the interceptor
     * on each request so it is always current.
     */
    private fun makeClient(): DashboardApiClient {
        val base = settings.baseUrlSnapshot()
        return if (base.isBlank()) {
            FakeDashboardApiClient()
        } else {
            ApiClientFactory.create(
                baseUrl = base,
                tokenProvider = { settings.tokenSnapshot() },
                enableLogging = BuildConfig.DEBUG,
            )
        }
    }

    /**
     * Re-render the Glance widget from the (already-updated) cache — no network.
     * Called after an in-app capture/chat so the home-screen widget reflects the
     * fresh Today payload immediately instead of waiting for the periodic worker.
     */
    suspend fun refreshWidgetFromCache() {
        if (::appContext.isInitialized) TodayWidget.updateAll(appContext)
    }
}
