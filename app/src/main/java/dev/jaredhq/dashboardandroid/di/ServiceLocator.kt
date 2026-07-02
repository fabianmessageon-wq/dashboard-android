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
import dev.jaredhq.dashboardandroid.data.repository.WatchHealthUploadQueue
import java.io.File
import dev.jaredhq.dashboardandroid.data.settings.SecureSettingsStore
import dev.jaredhq.dashboardandroid.data.settings.SettingsStore
import dev.jaredhq.dashboardandroid.watch.engine.CompositeWatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.IdoSdkWatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.UploadingWatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchUploadOutcome
import dev.jaredhq.dashboardandroid.watch.music.AndroidWatchMusicController
import dev.jaredhq.dashboardandroid.watch.music.WatchMusicController
import dev.jaredhq.dashboardandroid.watch.music.AndroidWatchSongImportPreparer
import dev.jaredhq.dashboardandroid.watch.schedule.WatchSchedulePusher
import dev.jaredhq.dashboardandroid.watch.weather.WeatherPusher
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

    /** App-lifetime main-thread scope for Android media-session callbacks and watch controls. */
    private val watchMusicScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Factory default: Fabian's Active 4 Pro. Overridable via [watchDeviceId] (persisted). */
    private const val DEFAULT_WATCH_MAC = "F4:91:29:51:C6:45"
    private const val WATCH_PREFS = "watch_pairing"
    private const val KEY_WATCH_MAC = "watch_mac"

    /**
     * The bound watch's MAC — the connect target and the dashboard device id. Persisted so a
     * replacement watch can be paired without a rebuild (first step of real pairing; a scan-and-pick
     * UI can write this). Reads fall back to the factory default.
     */
    var watchDeviceId: String
        get() = appContext.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WATCH_MAC, DEFAULT_WATCH_MAC) ?: DEFAULT_WATCH_MAC
        set(value) {
            appContext.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_WATCH_MAC, value.trim().uppercase()).apply()
        }

    /**
     * Optional second sink for decoded health records, set by the product Watch screen's ViewModel
     * so it can show live sync feedback. The engine's listener is a [CompositeWatchHealthListener]
     * that always uploads and additionally forwards here when non-null. Volatile: written from the
     * UI thread, read on the SDK callback thread.
     */
    @Volatile
    var watchUiListener: WatchHealthListener? = null

    /**
     * Optional sink for the dashboard upload result, set by the product Watch screen's ViewModel so
     * it can show whether the decoded sync actually reached the dashboard. Volatile: written from the
     * UI thread, read on the upload coroutine.
     */
    @Volatile
    var watchUploadListener: ((WatchUploadOutcome) -> Unit)? = null

    lateinit var settings: SettingsStore
        private set

    lateinit var cache: TodayCache
        private set

    lateinit var repository: DashboardRepository
        private set

    /**
     * The active watch data engine (ADR 0001). Currently the vendored-SDK engine
     * ([IdoSdkWatchEngine]); another implementation can be swapped in here behind the same
     * [WatchEngine] interface without touching callers.
     */
    lateinit var watchEngine: WatchEngine
        private set

    lateinit var watchMusicController: WatchMusicController
        private set

    lateinit var watchSongImportPreparer: AndroidWatchSongImportPreparer
        private set

    /** Hourly-rate-limited weather push to the watch (best-effort, keyless sources). */
    lateinit var weatherPusher: WeatherPusher
        private set

    /** Keeps the watch's native schedule/events screen in step with the dashboard feed. */
    lateinit var schedulePusher: WatchSchedulePusher
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
                // Durable spool so a failed watch-health POST survives to the next sync (the BLE
                // sync + listener flush have already cleared both the watch's and our buffers).
                watchUploadQueue = WatchHealthUploadQueue(
                    File(appContext.filesDir, "watch-upload-queue"),
                ),
            )

            // Vendored-SDK engine (ADR 0001). init() is idempotent; it loads the native lib
            // + opens the SDK's own DB once at startup. Guarded so a native-load/SDK-init
            // failure degrades the watch feature rather than crashing app startup.
            val application = appContext as? android.app.Application
                ?: throw IllegalStateException("ServiceLocator must be initialised with the Application context")
            watchEngine = IdoSdkWatchEngine(application).also {
                // Decoded health records flow to the dashboard via this uploader (W5). It buffers
                // per sync and uploads one idempotent batch on completion.
                // TODO: source deviceId from the connected device once the Watch UI drives
                //   connect (today the single bound Active 4 Pro MAC is the only device).
                val uploader = UploadingWatchHealthListener(
                    repository = repository,
                    scope = watchUploadScope,
                    deviceId = watchDeviceId,
                    // Forward the upload result to the Watch screen's listener when one is registered.
                    onUploadOutcome = { outcome -> watchUploadListener?.invoke(outcome) },
                )
                // Always upload; also forward to the Watch screen's listener when one is registered.
                it.listener = CompositeWatchHealthListener { listOfNotNull(uploader, watchUiListener) }
                runCatching { it.init() }.onFailure { e ->
                    android.util.Log.e("ServiceLocator", "Watch engine init failed", e)
                }
            }

            watchMusicController = AndroidWatchMusicController(
                context = appContext,
                engine = watchEngine,
                scope = watchMusicScope,
            )
            watchSongImportPreparer = AndroidWatchSongImportPreparer(appContext)
            weatherPusher = WeatherPusher(context = appContext, engine = watchEngine)
            schedulePusher = WatchSchedulePusher(
                context = appContext,
                engine = watchEngine,
                repository = repository,
            )

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
