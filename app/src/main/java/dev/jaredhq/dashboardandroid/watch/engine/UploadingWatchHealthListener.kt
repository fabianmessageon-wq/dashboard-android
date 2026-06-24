package dev.jaredhq.dashboardandroid.watch.engine

import android.util.Log
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [WatchHealthListener] that buffers the records decoded during a sync and uploads them to the
 * dashboard as one batch when the sync finishes ([DashboardRepository.uploadWatchHealth]).
 *
 * Why buffer-then-flush rather than upload per record: the SDK delivers many records per sync and
 * the dashboard upsert is keyed by date/start, so a single idempotent batch is cheaper and safe to
 * retry. We flush on **both** completion and failure because this watch's sync often reports a
 * benign post-transfer failure *after* all data has already been delivered — dropping the buffer on
 * failure would lose a complete dataset.
 *
 * Callbacks arrive on the SDK's (UI) thread; buffering is synchronized and the actual network
 * upload is launched on [scope] (off the callback thread). Each record is also logged for
 * on-device observability.
 */
class UploadingWatchHealthListener(
    private val repository: DashboardRepository,
    private val scope: CoroutineScope,
    private val deviceId: String,
) : WatchHealthListener {

    private val lock = Any()
    private val activityDays = mutableListOf<WatchActivityDay>()
    private val heartRateDays = mutableListOf<WatchHeartRateDay>()
    private val sleepSessions = mutableListOf<WatchSleepSession>()
    private val workouts = mutableListOf<WatchWorkout>()

    override fun onActivityDay(day: WatchActivityDay) {
        Log.i(TAG, "ACTIVITY $day")
        synchronized(lock) { activityDays += day }
    }

    override fun onHeartRateDay(day: WatchHeartRateDay) {
        Log.i(TAG, "HEART_RATE $day")
        synchronized(lock) { heartRateDays += day }
    }

    override fun onSleepSession(session: WatchSleepSession) {
        Log.i(TAG, "SLEEP $session")
        synchronized(lock) { sleepSessions += session }
    }

    override fun onWorkout(workout: WatchWorkout) {
        Log.i(TAG, "WORKOUT $workout")
        synchronized(lock) { workouts += workout }
    }

    override fun onSyncProgress(percent: Int) { Log.i(TAG, "sync progress $percent%") }

    override fun onSyncComplete() {
        Log.i(TAG, "sync complete — flushing")
        flush()
    }

    override fun onSyncFailed() {
        // May still hold a full dataset delivered before a benign end-of-sync failure — flush it.
        Log.w(TAG, "sync failed — flushing any buffered records")
        flush()
    }

    /** Drain the buffers into one batch and upload it (no-op if empty). */
    private fun flush() {
        val batch = synchronized(lock) {
            if (activityDays.isEmpty() && heartRateDays.isEmpty() &&
                sleepSessions.isEmpty() && workouts.isEmpty()
            ) {
                return
            }
            WatchHealthBatch(
                deviceId = deviceId,
                activityDays = activityDays.toList(),
                heartRateDays = heartRateDays.toList(),
                sleepSessions = sleepSessions.toList(),
                workouts = workouts.toList(),
            ).also {
                activityDays.clear()
                heartRateDays.clear()
                sleepSessions.clear()
                workouts.clear()
            }
        }

        scope.launch {
            repository.uploadWatchHealth(batch)
                .onSuccess { Log.i(TAG, "uploaded ${batch.recordCount} records (stored=${it.storedCount})") }
                .onFailure { Log.w(TAG, "health upload failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "WatchHealthUpload"
    }
}
