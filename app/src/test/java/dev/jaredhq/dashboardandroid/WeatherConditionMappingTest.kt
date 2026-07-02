package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.weather.OpenMeteoWeatherProvider
import dev.jaredhq.dashboardandroid.watch.engine.WatchWeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WMO weather-interpretation code → coarse watch condition. The full table lives in
 * [OpenMeteoWeatherProvider.conditionFor]; these pin the representative code of each family so
 * an accidental renumbering is caught without a network or a device.
 */
class WeatherConditionMappingTest {

    @Test
    fun mapsRepresentativeWmoCodes() {
        assertEquals(WatchWeatherCondition.CLEAR, OpenMeteoWeatherProvider.conditionFor(0))
        assertEquals(WatchWeatherCondition.PARTLY_CLOUDY, OpenMeteoWeatherProvider.conditionFor(2))
        assertEquals(WatchWeatherCondition.OVERCAST, OpenMeteoWeatherProvider.conditionFor(3))
        assertEquals(WatchWeatherCondition.FOG, OpenMeteoWeatherProvider.conditionFor(45))
        assertEquals(WatchWeatherCondition.DRIZZLE, OpenMeteoWeatherProvider.conditionFor(55))
        assertEquals(WatchWeatherCondition.RAIN, OpenMeteoWeatherProvider.conditionFor(63))
        assertEquals(WatchWeatherCondition.RAIN, OpenMeteoWeatherProvider.conditionFor(80))
        assertEquals(WatchWeatherCondition.HEAVY_RAIN, OpenMeteoWeatherProvider.conditionFor(65))
        assertEquals(WatchWeatherCondition.SLEET, OpenMeteoWeatherProvider.conditionFor(66))
        assertEquals(WatchWeatherCondition.SNOW, OpenMeteoWeatherProvider.conditionFor(75))
        assertEquals(WatchWeatherCondition.SNOW, OpenMeteoWeatherProvider.conditionFor(85))
        assertEquals(WatchWeatherCondition.THUNDERSTORM, OpenMeteoWeatherProvider.conditionFor(95))
        assertEquals(WatchWeatherCondition.OTHER, OpenMeteoWeatherProvider.conditionFor(-1))
        assertEquals(WatchWeatherCondition.OTHER, OpenMeteoWeatherProvider.conditionFor(42))
    }
}
