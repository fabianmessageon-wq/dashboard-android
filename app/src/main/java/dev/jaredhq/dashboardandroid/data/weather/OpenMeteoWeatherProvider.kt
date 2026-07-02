package dev.jaredhq.dashboardandroid.data.weather

import android.util.Log
import dev.jaredhq.dashboardandroid.watch.engine.WatchSunTime
import dev.jaredhq.dashboardandroid.watch.engine.WatchWeather
import dev.jaredhq.dashboardandroid.watch.engine.WatchWeatherCondition
import dev.jaredhq.dashboardandroid.watch.engine.WatchWeatherDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchWeatherHour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Weather source for the watch push: coarse IP geolocation (ipapi.co) + Open-Meteo forecast.
 * Both are keyless HTTPS APIs, deliberately chosen so the bridge needs no runtime location
 * permission (the manifest caps ACCESS_*_LOCATION at API 30 for BLE reasons) and no account.
 * City-level accuracy is plenty for a weather face.
 *
 * Returns null on any failure — callers treat weather as strictly best-effort.
 */
class OpenMeteoWeatherProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): WatchWeather? = withContext(Dispatchers.IO) {
        val loc = runCatching { geolocate() }
            .onFailure { Log.w(TAG, "geolocation failed", it) }.getOrNull() ?: return@withContext null
        runCatching { forecast(loc) }
            .onFailure { Log.w(TAG, "forecast fetch failed", it) }.getOrNull()
    }

    // ipwho.is first (keyless, tolerant of app clients); ipapi.co as fallback — the latter
    // 403s okhttp's default user-agent, hence the explicit UA on every request.
    private fun geolocate(): IpLocation? {
        get("https://ipwho.is/")?.let { body ->
            runCatching {
                val dto = json.decodeFromString<IpWhoDto>(body)
                if (dto.success != false && dto.latitude != null && dto.longitude != null) {
                    return IpLocation(dto.latitude, dto.longitude, dto.city ?: "")
                }
            }
        }
        val body = get("https://ipapi.co/json/") ?: return null
        val dto = json.decodeFromString<IpApiDto>(body)
        val lat = dto.latitude ?: return null
        val lon = dto.longitude ?: return null
        return IpLocation(lat, lon, dto.city ?: "")
    }

    private fun forecast(loc: IpLocation): WatchWeather? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${loc.latitude}&longitude=${loc.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,is_day,weather_code,wind_speed_10m," +
            "surface_pressure,uv_index" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset," +
            "precipitation_probability_max" +
            "&timezone=auto&forecast_days=6"
        val body = get(url) ?: return null
        val dto = json.decodeFromString<OpenMeteoDto>(body)
        val current = dto.current ?: return null
        val daily = dto.daily

        // Hourly arrays start at local midnight; slice the next 24h from the current hour.
        val hourly = buildList {
            val h = dto.hourly
            if (h?.time != null && h.temperature2m != null && h.weatherCode != null) {
                val nowIso = current.time // "2026-07-02T14:00" — same tz + format as hourly.time
                val start = h.time.indexOfFirst { it >= nowIso }.let { if (it < 0) 0 else it }
                for (i in start until minOf(start + 24, h.time.size)) {
                    add(
                        WatchWeatherHour(
                            condition = conditionFor(h.weatherCode.getOrNull(i) ?: -1),
                            temperatureC = h.temperature2m.getOrNull(i)?.roundToInt() ?: continue,
                            precipProbabilityPercent = h.precipitationProbability?.getOrNull(i),
                        ),
                    )
                }
            }
        }

        // Daily index 0 is today (min/max/sun times); 1.. are the future days.
        val futureDays = buildList {
            if (daily?.weatherCode != null) {
                for (i in 1 until daily.weatherCode.size) {
                    add(
                        WatchWeatherDay(
                            condition = conditionFor(daily.weatherCode[i]),
                            minTempC = daily.temperature2mMin?.getOrNull(i)?.roundToInt() ?: continue,
                            maxTempC = daily.temperature2mMax?.getOrNull(i)?.roundToInt() ?: continue,
                        ),
                    )
                }
            }
        }

        return WatchWeather(
            cityName = loc.city,
            condition = conditionFor(current.weatherCode),
            temperatureC = current.temperature2m.roundToInt(),
            minTempC = daily?.temperature2mMin?.firstOrNull()?.roundToInt()
                ?: current.temperature2m.roundToInt(),
            maxTempC = daily?.temperature2mMax?.firstOrNull()?.roundToInt()
                ?: current.temperature2m.roundToInt(),
            humidityPercent = current.relativeHumidity2m,
            uvIndex = current.uvIndex?.roundToInt(),
            precipProbabilityPercent = daily?.precipitationProbabilityMax?.firstOrNull(),
            windSpeedKmh = current.windSpeed10m?.roundToInt(),
            pressureHpa = current.surfacePressure?.toFloat(),
            sunrise = daily?.sunrise?.firstOrNull()?.let(::sunTimeFromIso),
            sunset = daily?.sunset?.firstOrNull()?.let(::sunTimeFromIso),
            hourly = hourly,
            daily = futureDays,
        )
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "dashboard-android-weather/1.0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $url → ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }

    /** "2026-07-02T07:12" → 07:12 local. */
    private fun sunTimeFromIso(iso: String): WatchSunTime? {
        val t = iso.substringAfter('T', "")
        val parts = t.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.take(2)?.toIntOrNull() ?: return null
        return WatchSunTime(hour, minute)
    }

    companion object {
        private const val TAG = "WeatherProvider"

        /** WMO weather-interpretation codes → our coarse condition taxonomy. */
        fun conditionFor(wmoCode: Int): WatchWeatherCondition = when (wmoCode) {
            0 -> WatchWeatherCondition.CLEAR
            1, 2 -> WatchWeatherCondition.PARTLY_CLOUDY
            3 -> WatchWeatherCondition.OVERCAST
            45, 48 -> WatchWeatherCondition.FOG
            51, 53, 55, 56, 57 -> WatchWeatherCondition.DRIZZLE
            61, 63, 80, 81 -> WatchWeatherCondition.RAIN
            65, 82 -> WatchWeatherCondition.HEAVY_RAIN
            66, 67 -> WatchWeatherCondition.SLEET
            71, 73, 75, 77, 85, 86 -> WatchWeatherCondition.SNOW
            95, 96, 99 -> WatchWeatherCondition.THUNDERSTORM
            else -> WatchWeatherCondition.OTHER
        }
    }
}

private data class IpLocation(val latitude: Double, val longitude: Double, val city: String)

@Serializable
private data class IpApiDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
)

@Serializable
private data class IpWhoDto(
    val success: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
)

@Serializable
private data class OpenMeteoDto(
    val current: OmCurrentDto? = null,
    val hourly: OmHourlyDto? = null,
    val daily: OmDailyDto? = null,
)

@Serializable
private data class OmCurrentDto(
    val time: String = "",
    @SerialName("temperature_2m") val temperature2m: Double = 0.0,
    @SerialName("relative_humidity_2m") val relativeHumidity2m: Int? = null,
    @SerialName("is_day") val isDay: Int? = null,
    @SerialName("weather_code") val weatherCode: Int = -1,
    @SerialName("wind_speed_10m") val windSpeed10m: Double? = null,
    @SerialName("surface_pressure") val surfacePressure: Double? = null,
    @SerialName("uv_index") val uvIndex: Double? = null,
)

@Serializable
private data class OmHourlyDto(
    val time: List<String>? = null,
    @SerialName("temperature_2m") val temperature2m: List<Double>? = null,
    @SerialName("weather_code") val weatherCode: List<Int>? = null,
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?>? = null,
)

@Serializable
private data class OmDailyDto(
    val time: List<String>? = null,
    @SerialName("weather_code") val weatherCode: List<Int>? = null,
    @SerialName("temperature_2m_max") val temperature2mMax: List<Double>? = null,
    @SerialName("temperature_2m_min") val temperature2mMin: List<Double>? = null,
    val sunrise: List<String>? = null,
    val sunset: List<String>? = null,
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?>? = null,
)
