package dev.jaredhq.dashboardandroid.watch.engine

/**
 * Engine-agnostic weather snapshot to push to the watch face. Temperatures are °C (the engine
 * applies whatever wire offset/units its protocol needs); times are local wall-clock. [hourly]
 * is the next hours in order starting from now; [daily] is upcoming days starting from tomorrow.
 */
data class WatchWeather(
    val cityName: String,
    val condition: WatchWeatherCondition,
    val temperatureC: Int,
    val minTempC: Int,
    val maxTempC: Int,
    val humidityPercent: Int? = null,
    val uvIndex: Int? = null,
    val precipProbabilityPercent: Int? = null,
    val windSpeedKmh: Int? = null,
    val pressureHpa: Float? = null,
    val sunrise: WatchSunTime? = null,
    val sunset: WatchSunTime? = null,
    val hourly: List<WatchWeatherHour> = emptyList(),
    val daily: List<WatchWeatherDay> = emptyList(),
)

/** Local wall-clock hour/minute. */
data class WatchSunTime(val hour: Int, val minute: Int)

data class WatchWeatherHour(
    val condition: WatchWeatherCondition,
    val temperatureC: Int,
    val precipProbabilityPercent: Int? = null,
)

data class WatchWeatherDay(
    val condition: WatchWeatherCondition,
    val minTempC: Int,
    val maxTempC: Int,
)

/**
 * Coarse condition taxonomy the watch faces can render. Deliberately close to the IDO icon set
 * (the only consumer today) but expressed in our own terms so another engine can map it too.
 */
enum class WatchWeatherCondition {
    CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    FOG,
    DRIZZLE,
    RAIN,
    HEAVY_RAIN,
    THUNDERSTORM,
    SLEET,
    SNOW,
    WINDY,
    OTHER,
}
