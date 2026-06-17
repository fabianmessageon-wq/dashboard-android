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

    lateinit var settings: SettingsStore
        private set

    lateinit var cache: TodayCache
        private set

    lateinit var repository: DashboardRepository
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext

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
}
