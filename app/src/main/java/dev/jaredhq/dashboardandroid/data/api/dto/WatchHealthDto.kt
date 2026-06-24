package dev.jaredhq.dashboardandroid.data.api.dto

import dev.jaredhq.dashboardandroid.watch.engine.WatchActivityDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthBatch
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult
import dev.jaredhq.dashboardandroid.watch.engine.WatchHeartRateDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchSleepSession
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
 * Health-only: unlike [WatchSyncDto] (Phase-2 connection telemetry) this carries no raw protocol
 * events. All metric fields are nullable — the watch reports 0/absent for unmeasured values and the
 * engine mappers surface those as null.
 */
@Serializable
data class WatchHealthUploadDto(
    val deviceId: String,
    val activityDays: List<WatchActivityDayDto> = emptyList(),
    val heartRateDays: List<WatchHeartRateDayDto> = emptyList(),
    val sleepSessions: List<WatchSleepSessionDto> = emptyList(),
    val workouts: List<WatchWorkoutDto> = emptyList(),
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
)

private val WORKOUT_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun WatchWorkout.toDto() = WatchWorkoutDto(
    date = startDateTime.take(10), // YYYY-MM-DD
    startedAt = workoutStartEpochSeconds(startDateTime),
    durationSeconds = durationSeconds,
    activityType = type,
    steps = steps,
    distanceMeters = distanceMeters,
    calories = calories,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    minHeartRate = minHeartRate,
)

/**
 * The watch reports session start as local wall-clock (no zone). Interpret it in the phone's zone
 * to an epoch — best available approximation; falls back to 0 if the string is unparseable
 * (e.g. an all-zero sentinel that slipped the engine guard).
 */
private fun workoutStartEpochSeconds(localDateTime: String): Long = runCatching {
    LocalDateTime.parse(localDateTime, WORKOUT_TIME_FMT)
        .atZone(ZoneId.systemDefault())
        .toEpochSecond()
}.getOrDefault(0L)

// ── DTO → domain ───────────────────────────────────────────────────────────────────

fun WatchHealthResponseDto.toDomain(): WatchHealthUploadResult =
    WatchHealthUploadResult(accepted = accepted, storedCount = storedCount)
