package dev.jaredhq.dashboardandroid.watch.engine

/**
 * Independent domain models for watch health data (ADR 0001).
 *
 * These are the app's OWN types — deliberately free of any `com.ido.*` / `com.veryfit.*`
 * reference — so that everything above the [WatchEngine] boundary (repository, upload DTOs,
 * UI) never depends on the vendored SDK. Only [IdoSdkWatchEngine] maps the SDK's typed
 * `HealthXxx` objects into these. When a path is later clean-roomed, these models stay put
 * and only the engine implementation changes.
 *
 * Field shapes mirror the dashboard `watch_*` tables (dashboard `schema/watch-health-metrics`)
 * so the upload DTOs are a thin projection.
 */

/** A whole-day activity rollup (steps/calories/distance + 5-zone minutes). */
data class WatchActivityDay(
    val date: String, // YYYY-MM-DD
    val steps: Int?,
    val distanceMeters: Int?,
    val calories: Int?,
    val durationSeconds: Int?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val minHeartRate: Int?,
    val warmUpMins: Int?,
    val burnFatMins: Int?,
    val aerobicMins: Int?,
    val anaerobicMins: Int?,
    val limitMins: Int?,
)

/** Daily resting heart rate + HR-zone thresholds and durations. */
data class WatchHeartRateDay(
    val date: String, // YYYY-MM-DD
    val restingBpm: Int?,
    val userMaxHr: Int?,
    val warmUpThreshold: Int?,
    val burnFatThreshold: Int?,
    val aerobicThreshold: Int?,
    val anaerobicThreshold: Int?,
    val limitThreshold: Int?,
    val warmUpMins: Int?,
    val burnFatMins: Int?,
    val aerobicMins: Int?,
    val anaerobicMins: Int?,
    val limitMins: Int?,
)

/** A night's sleep with deep/light/awake/REM breakdown, score, and V3 averages. */
data class WatchSleepSession(
    val date: String, // YYYY-MM-DD (wake date)
    // Sleep onset as local wall-clock "YYYY-MM-DD HH:MM:SS" (V3 fall-asleep time). Uploaded as an
    // epoch and used by the server to tell a nap apart from the main night that share a wake date
    // (without it a short nap upsert-clobbered the night). Null only if the device reports no onset.
    val startDateTime: String? = null,
    val totalMinutes: Int?,
    val deepMinutes: Int?,
    val lightMinutes: Int?,
    val awakeMinutes: Int?,
    val deepCount: Int?,
    val lightCount: Int?,
    val awakeCount: Int?,
    val score: Int?,
    val sleepEndHour: Int?,
    val sleepEndMinute: Int?,
    // V3-only richer fields; null on the v2 path (HealthSleep doesn't carry them).
    val remMinutes: Int? = null,
    val remCount: Int? = null,
    val avgHeartRate: Int? = null,
    val avgSpo2: Int? = null,
    val avgRespiratoryRate: Int? = null,
)

/**
 * A single recorded workout / sport session (V3 `HealthActivityV3`).
 *
 * The Active 4 Pro (and other V3 devices) deliver exercise sessions through the V3 sync path
 * rather than the v2 daily rollup; [type] is the SDK's raw activity-type code. Speeds are in the
 * SDK's raw units (cm/s-ish); leave unit normalisation to the upload/UI layer.
 */
data class WatchWorkout(
    val startDateTime: String, // YYYY-MM-DD HH:MM:SS
    val endDateTime: String,   // YYYY-MM-DD HH:MM:SS
    val type: Int,
    val durationSeconds: Int?,
    val calories: Int?,
    val distanceMeters: Int?,
    val steps: Int?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val minHeartRate: Int?,
    val avgSpeed: Int?,
    val maxSpeed: Int?,
    val trainingEffect: Int?,
    val vo2Max: Int?,
)

/** One GPS fix on a recorded route. */
data class WatchGpsPoint(
    val latitude: Double,
    val longitude: Double,
)

/**
 * A recorded GPS route (v2 `HealthGps` + its `HealthGpsItem` fixes). The watch reports a start
 * wall-clock + a fixed sampling interval; each point is [intervalSeconds] after the previous one.
 * Routes aren't wired to a workout id on the wire — the dashboard associates a route with the
 * workout that shares its start window at read time.
 */
data class WatchGpsRoute(
    val startDateTime: String, // YYYY-MM-DD HH:MM:SS
    val intervalSeconds: Int?,
    val points: List<WatchGpsPoint>,
)

/**
 * Intraday point metrics delivered through the V3 sync as a parent day record + a list of items.
 *
 * Each carries a [recordedAt] local wall-clock timestamp ("YYYY-MM-DD HH:MM:SS", the watch reports
 * no zone) so the upload layer can project it to an epoch the same way [WatchWorkout] does. The
 * watch encodes each item's time as an offset within the parent day; [IdoSdkWatchEngine] resolves
 * that to wall-clock using the IDO minute-of-day convention (temperature carries its own
 * `time_offset_unit`). The `date` prefix is always exact, so per-day rollups are correct even if the
 * within-day offset unit is ever revised after on-device confirmation.
 */
data class WatchSpo2Reading(
    val recordedAt: String, // YYYY-MM-DD HH:MM:SS
    val percent: Int,       // SpO2 0–100
)

/** A single HRV sample (heart-rate variability, milliseconds). */
data class WatchHrvReading(
    val recordedAt: String,
    val hrvMs: Int,
)

/** A single respiratory-rate sample (breaths per minute). */
data class WatchRespiratoryReading(
    val recordedAt: String,
    val breathsPerMinute: Int,
)

/** A single skin/body temperature sample, already scaled to degrees Celsius. */
data class WatchTemperatureReading(
    val recordedAt: String,
    val celsius: Double,
)

/** A single "body energy" / body-battery sample (0–100). */
data class WatchBodyEnergyReading(
    val recordedAt: String,
    val energy: Int,
)

/** A single blood-pressure sample (systolic/diastolic, mmHg) from the V3 BP path. */
data class WatchBloodPressureReading(
    val recordedAt: String,
    val systolic: Int,
    val diastolic: Int,
)

/** A single stress sample (0–100) from the IDO "pressure" metric. */
data class WatchStressReading(
    val recordedAt: String,
    val stressScore: Int,
)

/**
 * A single intraday heart-rate sample (bpm) from the V3 `HealthHeartRateSecond` series — the
 * Active 4 Pro's continuous HR (the v2 daily-HR summary path never fires on this device). Same
 * point-metric shape as the others: one [recordedAt] wall-clock per sample. Lands in the dashboard
 * `watch_heart_rate_readings` table.
 */
data class WatchHeartRateReading(
    val recordedAt: String, // YYYY-MM-DD HH:MM:SS
    val bpm: Int,
)

/**
 * Listener for decoded health data emitted by a [WatchEngine] during a sync.
 *
 * Methods fire on the SDK's callback thread (the IDO SDK marshals to the UI thread); the
 * implementer is responsible for moving work off it. Every metric callback may fire many
 * times in one sync (once per day/record); the lifecycle callbacks bracket the session.
 */
interface WatchHealthListener {
    fun onActivityDay(day: WatchActivityDay) {}
    fun onHeartRateDay(day: WatchHeartRateDay) {}
    fun onSleepSession(session: WatchSleepSession) {}
    /** A V3 workout/sport session (this is how V3 devices like the Active 4 Pro report exercise). */
    fun onWorkout(workout: WatchWorkout) {}

    /** A recorded GPS route (one complete route per call). */
    fun onGpsRoute(route: WatchGpsRoute) {}

    // V3 intraday point metrics (one call per sample).
    fun onSpo2Reading(reading: WatchSpo2Reading) {}
    fun onHrvReading(reading: WatchHrvReading) {}
    fun onRespiratoryReading(reading: WatchRespiratoryReading) {}
    fun onTemperatureReading(reading: WatchTemperatureReading) {}
    fun onBodyEnergyReading(reading: WatchBodyEnergyReading) {}
    fun onBloodPressureReading(reading: WatchBloodPressureReading) {}
    fun onStressReading(reading: WatchStressReading) {}
    fun onHeartRateReading(reading: WatchHeartRateReading) {}

    fun onSyncProgress(percent: Int) {}
    fun onSyncComplete() {}
    fun onSyncFailed() {}
}

/**
 * A batch of decoded health records from one sync, ready to upload to the dashboard
 * (`POST /api/widget/v1/watch/health`). [deviceId] is the watch MAC the records came from.
 */
data class WatchHealthBatch(
    val deviceId: String,
    val activityDays: List<WatchActivityDay> = emptyList(),
    val heartRateDays: List<WatchHeartRateDay> = emptyList(),
    val sleepSessions: List<WatchSleepSession> = emptyList(),
    val workouts: List<WatchWorkout> = emptyList(),
    val spo2Readings: List<WatchSpo2Reading> = emptyList(),
    val hrvReadings: List<WatchHrvReading> = emptyList(),
    val respiratoryReadings: List<WatchRespiratoryReading> = emptyList(),
    val temperatureReadings: List<WatchTemperatureReading> = emptyList(),
    val bodyEnergyReadings: List<WatchBodyEnergyReading> = emptyList(),
    val bloodPressureReadings: List<WatchBloodPressureReading> = emptyList(),
    val stressReadings: List<WatchStressReading> = emptyList(),
    val heartRateReadings: List<WatchHeartRateReading> = emptyList(),
    val gpsRoutes: List<WatchGpsRoute> = emptyList(),
) {
    val isEmpty: Boolean
        get() = activityDays.isEmpty() && heartRateDays.isEmpty() &&
            sleepSessions.isEmpty() && workouts.isEmpty() &&
            spo2Readings.isEmpty() && hrvReadings.isEmpty() &&
            respiratoryReadings.isEmpty() && temperatureReadings.isEmpty() &&
            bodyEnergyReadings.isEmpty() && bloodPressureReadings.isEmpty() &&
            stressReadings.isEmpty() && heartRateReadings.isEmpty() &&
            gpsRoutes.isEmpty()

    val recordCount: Int
        get() = activityDays.size + heartRateDays.size + sleepSessions.size + workouts.size +
            spo2Readings.size + hrvReadings.size + respiratoryReadings.size +
            temperatureReadings.size + bodyEnergyReadings.size +
            bloodPressureReadings.size + stressReadings.size + heartRateReadings.size +
            gpsRoutes.size
}

/**
 * Server acknowledgement of a [WatchHealthBatch] upload.
 *
 * [offline] is true when no real dashboard was contacted — the offline/fake client acknowledges
 * locally without persisting anything (happens when no dashboard URL is configured). Callers must
 * NOT report this as a successful upload.
 */
data class WatchHealthUploadResult(
    val accepted: Boolean,
    val storedCount: Int = 0,
    val offline: Boolean = false,
)

/**
 * Outcome of a dashboard upload attempt, surfaced to the Watch screen so a sync that decoded data
 * but failed to reach the dashboard is visible (rather than reading as a clean "completed" sync).
 *
 * [sentCount] is how many records the batch held; [storedCount] is the server's acknowledged count
 * (0 on failure). [error] is a short failure reason for the UI, or null on success.
 */
data class WatchUploadOutcome(
    val succeeded: Boolean,
    val sentCount: Int,
    val storedCount: Int = 0,
    val error: String? = null,
    /** True when nothing was actually persisted because no dashboard is configured (offline sink). */
    val offline: Boolean = false,
)
