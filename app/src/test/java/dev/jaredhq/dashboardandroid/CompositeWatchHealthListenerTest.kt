package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.watch.engine.CompositeWatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchActivityDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchBloodPressureReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchBodyEnergyReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchHeartRateDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchHrvReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchRespiratoryReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchSleepSession
import dev.jaredhq.dashboardandroid.watch.engine.WatchSpo2Reading
import dev.jaredhq.dashboardandroid.watch.engine.WatchStressReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchTemperatureReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchWorkout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for the single-listener fan-out that feeds upload + Watch-screen UI. */
class CompositeWatchHealthListenerTest {

    @Test
    fun fansEveryCallbackIncludingBloodPressureAndStressToAllDelegates() {
        val first = RecordingListener()
        val second = RecordingListener()
        val composite = CompositeWatchHealthListener { listOf(first, second) }

        composite.onActivityDay(activity)
        composite.onHeartRateDay(heartRate)
        composite.onSleepSession(sleep)
        composite.onWorkout(workout)
        composite.onSpo2Reading(spo2)
        composite.onHrvReading(hrv)
        composite.onRespiratoryReading(respiratory)
        composite.onTemperatureReading(temperature)
        composite.onBodyEnergyReading(bodyEnergy)
        composite.onBloodPressureReading(bloodPressure)
        composite.onStressReading(stress)
        composite.onSyncProgress(42)
        composite.onSyncComplete()
        composite.onSyncFailed()

        val expected = listOf(
            "activity", "heartRate", "sleep", "workout", "spo2", "hrv", "respiratory",
            "temperature", "bodyEnergy", "bloodPressure", "stress", "progress:42",
            "complete", "failed",
        )
        assertEquals(expected, first.events)
        assertEquals(expected, second.events)
    }

    @Test
    fun delegateExceptionDoesNotStopLaterDelegates() {
        val throwing = object : WatchHealthListener {
            override fun onStressReading(reading: WatchStressReading) {
                error("boom")
            }
        }
        val second = RecordingListener()
        val composite = CompositeWatchHealthListener { listOf(throwing, second) }

        composite.onStressReading(stress)

        assertEquals(listOf("stress"), second.events)
    }

    @Test
    fun delegatesAreResolvedPerCallback() {
        val first = RecordingListener()
        val second = RecordingListener()
        var delegates = listOf<WatchHealthListener>(first)
        val composite = CompositeWatchHealthListener { delegates }

        composite.onSyncProgress(1)
        delegates = listOf(second)
        composite.onSyncProgress(2)

        assertEquals(listOf("progress:1"), first.events)
        assertEquals(listOf("progress:2"), second.events)
    }

    private class RecordingListener : WatchHealthListener {
        val events = mutableListOf<String>()
        override fun onActivityDay(day: WatchActivityDay) { events += "activity" }
        override fun onHeartRateDay(day: WatchHeartRateDay) { events += "heartRate" }
        override fun onSleepSession(session: WatchSleepSession) { events += "sleep" }
        override fun onWorkout(workout: WatchWorkout) { events += "workout" }
        override fun onSpo2Reading(reading: WatchSpo2Reading) { events += "spo2" }
        override fun onHrvReading(reading: WatchHrvReading) { events += "hrv" }
        override fun onRespiratoryReading(reading: WatchRespiratoryReading) { events += "respiratory" }
        override fun onTemperatureReading(reading: WatchTemperatureReading) { events += "temperature" }
        override fun onBodyEnergyReading(reading: WatchBodyEnergyReading) { events += "bodyEnergy" }
        override fun onBloodPressureReading(reading: WatchBloodPressureReading) { events += "bloodPressure" }
        override fun onStressReading(reading: WatchStressReading) { events += "stress" }
        override fun onSyncProgress(percent: Int) { events += "progress:$percent" }
        override fun onSyncComplete() { events += "complete" }
        override fun onSyncFailed() { events += "failed" }
    }

    private companion object {
        val activity = WatchActivityDay(
            date = "2026-06-24", steps = 100, distanceMeters = 80, calories = 5,
            durationSeconds = 60, avgHeartRate = null, maxHeartRate = null, minHeartRate = null,
            warmUpMins = null, burnFatMins = null, aerobicMins = null, anaerobicMins = null,
            limitMins = null,
        )
        val heartRate = WatchHeartRateDay(
            date = "2026-06-24", restingBpm = 55, userMaxHr = null, warmUpThreshold = null,
            burnFatThreshold = null, aerobicThreshold = null, anaerobicThreshold = null,
            limitThreshold = null, warmUpMins = null, burnFatMins = null, aerobicMins = null,
            anaerobicMins = null, limitMins = null,
        )
        val sleep = WatchSleepSession(
            date = "2026-06-24", totalMinutes = 420, deepMinutes = null, lightMinutes = null,
            awakeMinutes = null, deepCount = null, lightCount = null, awakeCount = null,
            score = null, sleepEndHour = null, sleepEndMinute = null,
        )
        val workout = WatchWorkout(
            startDateTime = "2026-06-24 08:00:00", endDateTime = "2026-06-24 08:30:00",
            type = 1, durationSeconds = 1800, calories = 200, distanceMeters = 4000,
            steps = 5000, avgHeartRate = 130, maxHeartRate = 150, minHeartRate = 90,
            avgSpeed = null, maxSpeed = null, trainingEffect = null, vo2Max = null,
        )
        val spo2 = WatchSpo2Reading("2026-06-24 08:05:00", percent = 97)
        val hrv = WatchHrvReading("2026-06-24 08:06:00", hrvMs = 42)
        val respiratory = WatchRespiratoryReading("2026-06-24 08:07:00", breathsPerMinute = 14)
        val temperature = WatchTemperatureReading("2026-06-24 08:08:00", celsius = 36.5)
        val bodyEnergy = WatchBodyEnergyReading("2026-06-24 08:09:00", energy = 73)
        val bloodPressure = WatchBloodPressureReading("2026-06-24 08:10:00", systolic = 118, diastolic = 76)
        val stress = WatchStressReading("2026-06-24 08:11:00", stressScore = 38)
    }
}
