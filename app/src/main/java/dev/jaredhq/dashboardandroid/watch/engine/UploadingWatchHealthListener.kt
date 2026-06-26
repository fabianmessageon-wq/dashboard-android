package dev.jaredhq.dashboardandroid.watch.engine

import android.util.Log
import dev.jaredhq.dashboardandroid.BuildConfig
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
 * upload is launched on [scope] (off the callback thread). Detailed record bodies are logged only
 * in debug builds because these domain objects contain private decoded health values.
 */
class UploadingWatchHealthListener(
    private val repository: DashboardRepository,
    private val scope: CoroutineScope,
    private val deviceId: String,
    /**
     * Optional sink for the dashboard upload result, so the Watch screen can show whether the
     * decoded data actually reached the dashboard (the BLE sync succeeding does not imply the
     * upload did). Invoked on [scope]; defaults to a no-op for headless/worker uploads.
     */
    private val onUploadOutcome: (WatchUploadOutcome) -> Unit = {},
) : WatchHealthListener {

    private val lock = Any()
    private val activityDays = mutableListOf<WatchActivityDay>()
    private val heartRateDays = mutableListOf<WatchHeartRateDay>()
    private val sleepSessions = mutableListOf<WatchSleepSession>()
    private val workouts = mutableListOf<WatchWorkout>()
    private val spo2Readings = mutableListOf<WatchSpo2Reading>()
    private val hrvReadings = mutableListOf<WatchHrvReading>()
    private val respiratoryReadings = mutableListOf<WatchRespiratoryReading>()
    private val temperatureReadings = mutableListOf<WatchTemperatureReading>()
    private val bodyEnergyReadings = mutableListOf<WatchBodyEnergyReading>()
    private val bloodPressureReadings = mutableListOf<WatchBloodPressureReading>()
    private val stressReadings = mutableListOf<WatchStressReading>()

    override fun onActivityDay(day: WatchActivityDay) {
        logPrivateRecord("ACTIVITY", day)
        synchronized(lock) { activityDays += day }
    }

    override fun onHeartRateDay(day: WatchHeartRateDay) {
        logPrivateRecord("HEART_RATE", day)
        synchronized(lock) { heartRateDays += day }
    }

    override fun onSleepSession(session: WatchSleepSession) {
        logPrivateRecord("SLEEP", session)
        synchronized(lock) { sleepSessions += session }
    }

    override fun onWorkout(workout: WatchWorkout) {
        logPrivateRecord("WORKOUT", workout)
        synchronized(lock) { workouts += workout }
    }

    override fun onSpo2Reading(reading: WatchSpo2Reading) {
        logPrivateRecord("SPO2", reading)
        synchronized(lock) { spo2Readings += reading }
    }

    override fun onHrvReading(reading: WatchHrvReading) {
        logPrivateRecord("HRV", reading)
        synchronized(lock) { hrvReadings += reading }
    }

    override fun onRespiratoryReading(reading: WatchRespiratoryReading) {
        logPrivateRecord("RESP", reading)
        synchronized(lock) { respiratoryReadings += reading }
    }

    override fun onTemperatureReading(reading: WatchTemperatureReading) {
        logPrivateRecord("TEMP", reading)
        synchronized(lock) { temperatureReadings += reading }
    }

    override fun onBodyEnergyReading(reading: WatchBodyEnergyReading) {
        logPrivateRecord("BODY_ENERGY", reading)
        synchronized(lock) { bodyEnergyReadings += reading }
    }

    override fun onBloodPressureReading(reading: WatchBloodPressureReading) {
        logPrivateRecord("BLOOD_PRESSURE", reading)
        synchronized(lock) { bloodPressureReadings += reading }
    }

    override fun onStressReading(reading: WatchStressReading) {
        logPrivateRecord("STRESS", reading)
        synchronized(lock) { stressReadings += reading }
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
                sleepSessions.isEmpty() && workouts.isEmpty() &&
                spo2Readings.isEmpty() && hrvReadings.isEmpty() &&
                respiratoryReadings.isEmpty() && temperatureReadings.isEmpty() &&
                bodyEnergyReadings.isEmpty() && bloodPressureReadings.isEmpty() &&
                stressReadings.isEmpty()
            ) {
                return
            }
            WatchHealthBatch(
                deviceId = deviceId,
                activityDays = activityDays.toList(),
                heartRateDays = heartRateDays.toList(),
                sleepSessions = sleepSessions.toList(),
                workouts = workouts.toList(),
                spo2Readings = spo2Readings.toList(),
                hrvReadings = hrvReadings.toList(),
                respiratoryReadings = respiratoryReadings.toList(),
                temperatureReadings = temperatureReadings.toList(),
                bodyEnergyReadings = bodyEnergyReadings.toList(),
                bloodPressureReadings = bloodPressureReadings.toList(),
                stressReadings = stressReadings.toList(),
            ).also {
                activityDays.clear()
                heartRateDays.clear()
                sleepSessions.clear()
                workouts.clear()
                spo2Readings.clear()
                hrvReadings.clear()
                respiratoryReadings.clear()
                temperatureReadings.clear()
                bodyEnergyReadings.clear()
                bloodPressureReadings.clear()
                stressReadings.clear()
            }
        }

        scope.launch {
            repository.uploadWatchHealth(batch)
                .onSuccess {
                    Log.i(TAG, "uploaded ${batch.recordCount} records (stored=${it.storedCount})")
                    onUploadOutcome(
                        WatchUploadOutcome(
                            succeeded = it.accepted,
                            sentCount = batch.recordCount,
                            storedCount = it.storedCount,
                            error = if (it.accepted) null else "Dashboard rejected the upload.",
                        ),
                    )
                }
                .onFailure {
                    Log.w(TAG, "health upload failed: ${it.message}")
                    onUploadOutcome(
                        WatchUploadOutcome(
                            succeeded = false,
                            sentCount = batch.recordCount,
                            error = it.message ?: "Upload failed.",
                        ),
                    )
                }
        }
    }

    private fun logPrivateRecord(kind: String, record: Any) {
        if (BuildConfig.DEBUG) Log.i(TAG, "$kind $record")
    }

    private companion object {
        const val TAG = "WatchHealthUpload"
    }
}
