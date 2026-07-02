package dev.jaredhq.dashboardandroid.watch.weather

import android.content.Context
import android.util.Log
import dev.jaredhq.dashboardandroid.data.weather.OpenMeteoWeatherProvider
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes weather to the watch, rate-limited to once an hour like VeryFit. Callers just invoke
 * [pushIfDue] from every "the link is up and settled" moment — after a sync, on the connection
 * service's periodic tick — and this decides whether a push is actually warranted. Best-effort
 * throughout: any failure is logged and retried at the next opportunity.
 */
class WeatherPusher(
    context: Context,
    private val engine: WatchEngine,
    private val provider: OpenMeteoWeatherProvider = OpenMeteoWeatherProvider(),
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("weather_push", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /**
     * Fetch + push if the watch supports weather, the link is up, and the last successful push is
     * older than [intervalMs] (or [force]). Serialized so overlapping callers can't double-fetch.
     */
    suspend fun pushIfDue(force: Boolean = false) {
        mutex.withLock {
            // Capability check before any network I/O; null = table unknown yet, try next tick.
            if (engine.supportsWeatherPush() != true) return
            val last = prefs.getLong(KEY_LAST_PUSH_MS, 0L)
            if (!force && System.currentTimeMillis() - last < intervalMs) return

            val weather = provider.fetch() ?: return
            if (engine.pushWeather(weather)) {
                prefs.edit()
                    .putLong(KEY_LAST_PUSH_MS, System.currentTimeMillis())
                    .putString(KEY_LAST_CITY, weather.cityName)
                    .apply()
                Log.i(TAG, "weather pushed (${weather.cityName})")
            }
        }
    }

    companion object {
        private const val TAG = "WeatherPusher"
        private const val KEY_LAST_PUSH_MS = "last_push_ms"
        private const val KEY_LAST_CITY = "last_city"

        /** VeryFit resends at most hourly; a bit under so a 1h caller tick never just misses it. */
        const val DEFAULT_INTERVAL_MS = 55 * 60 * 1000L
    }
}
