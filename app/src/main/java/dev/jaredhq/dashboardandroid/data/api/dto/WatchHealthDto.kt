package dev.jaredhq.dashboardandroid.data.api.dto

import dev.jaredhq.dashboardandroid.watch.engine.WatchActivityDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchBloodPressureReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchBodyEnergyReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthBatch
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult
import dev.jaredhq.dashboardandroid.watch.engine.WatchHeartRateDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchHeartRateReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchHrvReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchRespiratoryReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchSleepSession
import dev.jaredhq.dashboardandroid.watch.engine.WatchSpo2Reading
import dev.jaredhq.dashboardandroid.watch.engine.WatchStressReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchTemperatureReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchWorkout
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Wire DTOs for `POST /api/widget/v1/watch/health` — decoded health records from a watch sync.
 *
 * Each list projects directly onto a dashboard `watch_*` table (dashboard
 * `schema/watch-health-metrics`): activity days, heart-rate days, sleep sessions, and workout
 * sessions. The server upserts day-keyed records by `(user, date)` and workouts by
 * `(user, startedAt)`, so re-uploading the same sync is idempotent.
 *
 * Health-only: this carries no raw protocol events — only decoded metrics. All metric fields are
 * nullable — the watch reports 0/absent for unmeasured values and the
 * engine mappers surface those as null.
 */
@Serializable
data class WatchHealthUploadDto(
    val deviceId: String,
    val activityDays: List<WatchActivityDayDto> = emptyList(),
    val heartRateDays: List<WatchHeartRateDayDto> = emptyList(),
    val sleepSessions: List<WatchSleepSessionDto> = emptyList(),
    val workouts: List<WatchWorkoutDto> = emptyList(),
    val spo2Readings: List<WatchSpo2ReadingDto> = emptyList(),
    val hrvReadings: List<WatchHrvReadingDto> = emptyList(),
    val respiratoryReadings: List<WatchRespiratoryReadingDto> = emptyList(),
    val temperatureReadings: List<WatchTemperatureReadingDto> = emptyList(),
    val bodyEnergyReadings: List<WatchBodyEnergyReadingDto> = emptyList(),
    val bloodPressureReadings: List<WatchBloodPressureReadingDto> = emptyList(),
    val stressReadings: List<WatchStressReadingDto> = emptyList(),
    val heartRateReadings: List<WatchHeartRateReadingDto> = emptyList(),
)

@Serializable
data class WatchActivityDayDto(
    val date: String,
    val steps: Int? = null,
    val distanceMeters: Int? = null,
    val calories: Int? = null,
    val durationSeconds: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val minHeartRate: Int? = null,
    val warmUpMins: Int? = null,
    val burnFatMins: Int? = null,
    val aerobicMins: Int? = null,
    val anaerobicMins: Int? = null,
    val limitMins: Int? = null,
)

@Serializable
data class WatchHeartRateDayDto(
    val date: String,
    val restingBpm: Int? = null,
    val userMaxHr: Int? = null,
    val warmUpThreshold: Int? = null,
    val burnFatThreshold: Int? = null,
    val aerobicThreshold: Int? = null,
    val anaerobicThreshold: Int? = null,
    val limitThreshold: Int? = null,
    val warmUpMins: Int? = null,
    val burnFatMins: Int? = null,
    val aerobicMins: Int? = null,
    val anaerobicMins: Int? = null,
    val limitMins: Int? = null,
)

@Serializable
data class WatchSleepSessionDto(
    val date: String,
    /** Epoch seconds of sleep onset (fall-asleep). Lets the server keep naps + the main night that
     *  share a wake date as distinct rows instead of upsert-clobbering one with the other. */
    val startedAt: Long? = null,
    val totalMinutes: Int? = null,
    val deepMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val awakeMinutes: Int? = null,
    val deepCount: Int? = null,
    val lightCount: Int? = null,
    val awakeCount: Int? = null,
    val score: Int? = null,
    val sleepEndHour: Int? = null,
    val sleepEndMinute: Int? = null,
    val remMinutes: Int? = null,
    val remCount: Int? = null,
    val avgHeartRate: Int? = null,
    val avgSpo2: Int? = null,
    val avgRespiratoryRate: Int? = null,
)

@Serializable
data class WatchWorkoutDto(
    val date: String,
    /** Epoch seconds of the session start (watch local time interpreted in the phone's zone). */
    val startedAt: Long,
    val durationSeconds: Int? = null,
    val activityType: Int? = null,
    val steps: Int? = null,
    val distanceMeters: Int? = null,
    val calories: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val minHeartRate: Int? = null,
)

// V3 intraday point metrics. Each row is one sample: a `date` (YYYY-MM-DD, for daily grouping) plus
// `recordedAt` epoch seconds (the watch's local wall-clock interpreted in the phone's zone). The
// server upserts by (user, recordedAt) per table so re-uploading a sync is idempotent.

@Serializable
data class WatchSpo2ReadingDto(
    val date: String,
    val recordedAt: Long,
    val percent: Int,
)

@Serializable
data class WatchHrvReadingDto(
    val date: String,
    val recordedAt: Long,
    val hrvMs: Int,
)

@Serializable
data class WatchRespiratoryReadingDto(
    val date: String,
    val recordedAt: Long,
    val breathsPerMinute: Int,
)

@Serializable
data class WatchTemperatureReadingDto(
    val date: String,
    val recordedAt: Long,
    val celsius: Double,
)

@Serializable
data class WatchBodyEnergyReadingDto(
    val date: String,
    val recordedAt: Long,
    val energy: Int,
)

@Serializable
data class WatchBloodPressureReadingDto(
    val date: String,
    val recordedAt: Long,
    val systolic: Int,
    val diastolic: Int,
)

@Serializable
data class WatchStressReadingDto(
    val date: String,
    val recordedAt: Long,
    val stressScore: Int,
)

@Serializable
data class WatchHeartRateReadingDto(
    val date: String,
    val recordedAt: Long,
    val bpm: Int,
)

/**
 * Server acknowledgement. Defensively defaulted like the other widget DTOs so a contract skew (or
 * an empty 2xx body) degrades to "accepted" rather than failing the sync.
 */
@Serializable
data class WatchHealthResponseDto(
    val accepted: Boolean = true,
    val storedCount: Int = 0,
)

// ── domain → DTO ───────────────────────────────────────────────────────────────────

fun WatchHealthBatch.toDto(): WatchHealthUploadDto = WatchHealthUploadDto(
    deviceId = deviceId,
    activityDays = activityDays.map { it.toDto() },
    heartRateDays = heartRateDays.map { it.toDto() },
    sleepSessions = sleepSessions.map { it.toDto() },
    workouts = workouts.map { it.toDto() },
    spo2Readings = spo2Readings.map { it.toDto() },
    hrvReadings = hrvReadings.map { it.toDto() },
    respiratoryReadings = respiratoryReadings.map { it.toDto() },
    temperatureReadings = temperatureReadings.map { it.toDto() },
    bodyEnergyReadings = bodyEnergyReadings.map { it.toDto() },
    bloodPressureReadings = bloodPressureReadings.map { it.toDto() },
    stressReadings = stressReadings.map { it.toDto() },
    heartRateReadings = heartRateReadings.map { it.toDto() },
)

private fun WatchActivityDay.toDto() = WatchActivityDayDto(
    date = date,
    steps = steps,
    distanceMeters = distanceMeters,
    calories = calories,
    durationSeconds = durationSeconds,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    minHeartRate = minHeartRate,
    warmUpMins = warmUpMins,
    burnFatMins = burnFatMins,
    aerobicMins = aerobicMins,
    anaerobicMins = anaerobicMins,
    limitMins = limitMins,
)

private fun WatchHeartRateDay.toDto() = WatchHeartRateDayDto(
    date = date,
    restingBpm = restingBpm,
    userMaxHr = userMaxHr,
    warmUpThreshold = warmUpThreshold,
    burnFatThreshold = burnFatThreshold,
    aerobicThreshold = aerobicThreshold,
    anaerobicThreshold = anaerobicThreshold,
    limitThreshold = limitThreshold,
    warmUpMins = warmUpMins,
    burnFatMins = burnFatMins,
    aerobicMins = aerobicMins,
    anaerobicMins = anaerobicMins,
    limitMins = limitMins,
)

private fun WatchSleepSession.toDto() = WatchSleepSessionDto(
    date = date,
    startedAt = startDateTime?.let { localWallClockEpochSeconds(it) },
    totalMinutes = totalMinutes,
    deepMinutes = deepMinutes,
    lightMinutes = lightMinutes,
    awakeMinutes = awakeMinutes,
    deepCount = deepCount,
    lightCount = lightCount,
    awakeCount = awakeCount,
    score = score,
    sleepEndHour = sleepEndHour,
    sleepEndMinute = sleepEndMinute,
    remMinutes = remMinutes,
    remCount = remCount,
    avgHeartRate = avgHeartRate,
    avgSpo2 = avgSpo2,
    avgRespiratoryRate = avgRespiratoryRate,
)

private val WORKOUT_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun WatchWorkout.toDto() = WatchWorkoutDto(
    date = startDateTime.take(10), // YYYY-MM-DD
    startedAt = localWallClockEpochSeconds(startDateTime),
    durationSeconds = durationSeconds,
    activityType = type,
    steps = steps,
    distanceMeters = distanceMeters,
    calories = calories,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    minHeartRate = minHeartRate,
)

private fun WatchSpo2Reading.toDto() = WatchSpo2ReadingDto(
    date = recordedAt.take(10),
    recordedAt = localWallClockEpochSeconds(recordedAt),
    percent = percent,
)

private fun WatchHrvReading.toDto() = WatchHrvReadingDto(
    date = recordedAt.take(10),
    recordedAt = localWallClockEpochSeconds(recordedAt),
    hrvMs = hrvMs,
)

private fun WatchRespiratoryReading.toDto() = WatchRespiratoryReadingDto(
    date = recordedAt.take(10),
    recordedAt = localWallClockEpochSeconds(recordedAt),
    breathsPerMinute = breathsPerMinute,
)

private fun WatchTemperatureReading.toDto() = WatchTemperatureReadingDto(
    date = recordedAt.take(10),
    recordedAt = localWallClockEpochSeconds(recordedAt),
    celsius = celsius,
)

private fun WatchBodyEnergyReading.toDto() = WatchBodyEnergyReadingDto(
    date = recordedAt.take(10),
    recordedAt = localWallClockEpochSeconds(recordedAt),
    energy = energy,
)

private fun WatchBloodPressureReading.toDto() = WatchBloodPressureReadingDto(
    date = recordedAt.take(10),
    recordedAt = localWallClockEpochSeconds(recordedAt),
    systolic = systolic,
    diastolic = diastolic,
)

private fun WatchStressReading.toDto() = WatchStressReadingDto(
    date = recordedAt.take(10),
    recordedAt = localWallClockEpochSeconds(recordedAt),
    stressScore = stressScore,
)

private fun WatchHeartRateReading.toDto() = WatchHeartRateReadingDto(
    date = recordedAt.take(10),
    recordedAt = localWallClockEpochSeconds(recordedAt),
    bpm = bpm,
)

/**
 * The watch reports times as local wall-clock (no zone). Interpret a "YYYY-MM-DD HH:MM:SS" string in
 * the phone's zone to an epoch — best available approximation; falls back to 0 if the string is
 * unparseable (e.g. an all-zero sentinel that slipped the engine guard).
 */
private fun localWallClockEpochSeconds(localDateTime: String): Long = runCatching {
    LocalDateTime.parse(localDateTime, WORKOUT_TIME_FMT)
        .atZone(ZoneId.systemDefault())
        .toEpochSecond()
}.getOrDefault(0L)

// ── DTO → domain ───────────────────────────────────────────────────────────────────

fun WatchHealthResponseDto.toDomain(): WatchHealthUploadResult =
    WatchHealthUploadResult(accepted = accepted, storedCount = storedCount)
