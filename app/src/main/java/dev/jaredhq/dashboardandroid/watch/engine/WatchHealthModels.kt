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

/** A night's sleep with deep/light/awake breakdown and score. */
data class WatchSleepSession(
    val date: String, // YYYY-MM-DD (wake date)
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

    fun onSyncProgress(percent: Int) {}
    fun onSyncComplete() {}
    fun onSyncFailed() {}
}
