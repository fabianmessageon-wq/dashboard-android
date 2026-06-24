package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.dto.toDto
import dev.jaredhq.dashboardandroid.watch.engine.WatchBodyEnergyReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthBatch
import dev.jaredhq.dashboardandroid.watch.engine.WatchHrvReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchRespiratoryReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchSpo2Reading
import dev.jaredhq.dashboardandroid.watch.engine.WatchTemperatureReading
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * W6: the V3 intraday point metrics (SpO2 / HRV / respiratory / temperature / body energy) project
 * from domain → wire DTO. Each reading's local wall-clock string becomes a `date` prefix plus a
 * `recordedAt` epoch interpreted in the phone's zone (the same convention as workouts).
 */
class WatchHealthMappingTest {

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Epoch the DTO is expected to produce for a local wall-clock string (system zone). */
    private fun expectedEpoch(local: String): Long =
        LocalDateTime.parse(local, fmt).atZone(ZoneId.systemDefault()).toEpochSecond()

    @Test
    fun pointMetricsCarryDateEpochAndValue() {
        val batch = WatchHealthBatch(
            deviceId = "f4:91:29:51:c6:45",
            spo2Readings = listOf(WatchSpo2Reading("2026-06-24 08:30:00", percent = 97)),
            hrvReadings = listOf(WatchHrvReading("2026-06-24 02:15:00", hrvMs = 42)),
            respiratoryReadings = listOf(
                WatchRespiratoryReading("2026-06-24 03:00:00", breathsPerMinute = 14),
            ),
            temperatureReadings = listOf(
                WatchTemperatureReading("2026-06-24 09:45:00", celsius = 36.5),
            ),
            bodyEnergyReadings = listOf(WatchBodyEnergyReading("2026-06-24 12:00:00", energy = 73)),
        )

        val dto = batch.toDto()

        val spo2 = dto.spo2Readings.single()
        assertEquals("2026-06-24", spo2.date)
        assertEquals(expectedEpoch("2026-06-24 08:30:00"), spo2.recordedAt)
        assertEquals(97, spo2.percent)

        val hrv = dto.hrvReadings.single()
        assertEquals("2026-06-24", hrv.date)
        assertEquals(expectedEpoch("2026-06-24 02:15:00"), hrv.recordedAt)
        assertEquals(42, hrv.hrvMs)

        val resp = dto.respiratoryReadings.single()
        assertEquals(expectedEpoch("2026-06-24 03:00:00"), resp.recordedAt)
        assertEquals(14, resp.breathsPerMinute)

        val temp = dto.temperatureReadings.single()
        assertEquals(expectedEpoch("2026-06-24 09:45:00"), temp.recordedAt)
        assertEquals(36.5, temp.celsius, 0.0001)

        val energy = dto.bodyEnergyReadings.single()
        assertEquals(expectedEpoch("2026-06-24 12:00:00"), energy.recordedAt)
        assertEquals(73, energy.energy)
    }

    @Test
    fun recordCountIncludesPointMetrics() {
        val batch = WatchHealthBatch(
            deviceId = "f4:91:29:51:c6:45",
            spo2Readings = listOf(WatchSpo2Reading("2026-06-24 08:30:00", percent = 97)),
            bodyEnergyReadings = listOf(WatchBodyEnergyReading("2026-06-24 12:00:00", energy = 73)),
        )
        assertEquals(2, batch.recordCount)
    }
}
