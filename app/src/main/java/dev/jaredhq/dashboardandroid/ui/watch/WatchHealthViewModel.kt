package dev.jaredhq.dashboardandroid.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jaredhq.dashboardandroid.watch.engine.WatchActivityDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchBloodPressureReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchBodyEnergyReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchHeartRateDay
import dev.jaredhq.dashboardandroid.watch.engine.WatchHrvReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchNotification
import dev.jaredhq.dashboardandroid.watch.engine.WatchRespiratoryReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchSleepSession
import dev.jaredhq.dashboardandroid.watch.engine.WatchSpo2Reading
import dev.jaredhq.dashboardandroid.watch.engine.WatchStressReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchTemperatureReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchUploadOutcome
import dev.jaredhq.dashboardandroid.watch.engine.WatchWorkout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Per-metric tally of records seen in a sync (live during, snapshotted after). */
data class WatchSyncCounts(
    val activityDays: Int = 0,
    val heartRateDays: Int = 0,
    val sleepSessions: Int = 0,
    val workouts: Int = 0,
    val spo2: Int = 0,
    val hrv: Int = 0,
    val respiratory: Int = 0,
    val temperature: Int = 0,
    val bodyEnergy: Int = 0,
    val bloodPressure: Int = 0,
    val stress: Int = 0,
) {
    val total: Int
        get() = activityDays + heartRateDays + sleepSessions + workouts +
            spo2 + hrv + respiratory + temperature + bodyEnergy +
            bloodPressure + stress
}

/** Result of the dashboard upload that follows a sync, for the "Last sync" card. */
data class WatchUploadStatus(
    val succeeded: Boolean,
    val sentCount: Int,
    val storedCount: Int,
    val error: String?,
    /** True when nothing was persisted because no dashboard is configured (offline sink). */
    val offline: Boolean = false,
)

/** A finished sync run's outcome, for the "Last sync" card. */
data class WatchSyncSummary(
    val at: String,          // local HH:mm:ss
    val succeeded: Boolean,  // false = ended on a failure (often the benign end-of-run step)
    val counts: WatchSyncCounts,
    /** Dashboard upload result, or null until the post-sync upload reports back. */
    val upload: WatchUploadStatus? = null,
)

/** Immutable snapshot the product Watch screen renders. */
data class WatchHealthUiState(
    val connection: WatchEngineConnectionState = WatchEngineConnectionState.DISCONNECTED,
    val hasPermissions: Boolean = false,
    val permissionRationale: String? = null,
    /** Records seen so far in the in-flight sync (resets when a sync starts). */
    val liveCounts: WatchSyncCounts = WatchSyncCounts(),
    /** The most recent finished sync, or null if none this session. */
    val lastSync: WatchSyncSummary? = null,
    /** Transient feedback for the last "send test notification" press (W7), or null. */
    val notificationHint: String? = null,
) {
    val syncing: Boolean get() = connection == WatchEngineConnectionState.SYNCING
}

/**
 * Drives the product Watch screen against the [WatchEngine] boundary (ADR 0001). It observes
 * [WatchEngine.connectionState]
 * for the lifecycle and registers a [WatchHealthListener] (via [registerUiListener]) to tally the
 * records each sync delivers, so the screen can confirm data actually flowed.
 *
 * The decoded health goes to the dashboard through the engine's upload listener; this VM only
 * counts records for feedback (no local persistence).
 *
 * @param deviceId the watch MAC to connect to (the single bound Active 4 Pro for now).
 * @param registerUiListener installs/removes this VM's listener as the engine's UI sink; the
 *   factory wires it to `ServiceLocator.watchUiListener`. Passed `null` on clear.
 */
class WatchHealthViewModel(
    private val engine: WatchEngine,
    private val deviceId: String,
    private val registerUiListener: (WatchHealthListener?) -> Unit,
    private val registerUploadListener: (((WatchUploadOutcome) -> Unit)?) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(WatchHealthUiState())
    val state: StateFlow<WatchHealthUiState> = _state.asStateFlow()

    private val uiListener = object : WatchHealthListener {
        override fun onActivityDay(day: WatchActivityDay) = bump { it.copy(activityDays = it.activityDays + 1) }
        override fun onHeartRateDay(day: WatchHeartRateDay) = bump { it.copy(heartRateDays = it.heartRateDays + 1) }
        override fun onSleepSession(session: WatchSleepSession) = bump { it.copy(sleepSessions = it.sleepSessions + 1) }
        override fun onWorkout(workout: WatchWorkout) = bump { it.copy(workouts = it.workouts + 1) }
        override fun onSpo2Reading(reading: WatchSpo2Reading) = bump { it.copy(spo2 = it.spo2 + 1) }
        override fun onHrvReading(reading: WatchHrvReading) = bump { it.copy(hrv = it.hrv + 1) }
        override fun onRespiratoryReading(reading: WatchRespiratoryReading) = bump { it.copy(respiratory = it.respiratory + 1) }
        override fun onTemperatureReading(reading: WatchTemperatureReading) = bump { it.copy(temperature = it.temperature + 1) }
        override fun onBodyEnergyReading(reading: WatchBodyEnergyReading) = bump { it.copy(bodyEnergy = it.bodyEnergy + 1) }
        override fun onBloodPressureReading(reading: WatchBloodPressureReading) = bump { it.copy(bloodPressure = it.bloodPressure + 1) }
        override fun onStressReading(reading: WatchStressReading) = bump { it.copy(stress = it.stress + 1) }
        override fun onSyncComplete() = finishSync(succeeded = true)
        override fun onSyncFailed() = finishSync(succeeded = false)
    }

    /** Dashboard upload result for the most recent sync; attaches to [WatchSyncSummary.upload]. */
    private val uploadListener: (WatchUploadOutcome) -> Unit = { outcome ->
        _state.update { prev ->
            val last = prev.lastSync ?: return@update prev
            prev.copy(
                lastSync = last.copy(
                    upload = WatchUploadStatus(
                        succeeded = outcome.succeeded,
                        sentCount = outcome.sentCount,
                        storedCount = outcome.storedCount,
                        error = outcome.error,
                        offline = outcome.offline,
                    ),
                ),
            )
        }
    }

    init {
        registerUiListener(uiListener)
        registerUploadListener(uploadListener)
        viewModelScope.launch {
            engine.connectionState.collect { conn ->
                _state.update { prev ->
                    // Entering a fresh sync clears the live tally so counts reflect only this run.
                    val resetLive = conn == WatchEngineConnectionState.SYNCING &&
                        prev.connection != WatchEngineConnectionState.SYNCING
                    prev.copy(
                        connection = conn,
                        liveCounts = if (resetLive) WatchSyncCounts() else prev.liveCounts,
                    )
                }
            }
        }
    }

    private fun bump(update: (WatchSyncCounts) -> WatchSyncCounts) {
        _state.update { it.copy(liveCounts = update(it.liveCounts)) }
    }

    private fun finishSync(succeeded: Boolean) {
        _state.update {
            it.copy(
                lastSync = WatchSyncSummary(
                    at = LocalTime.now().format(TIME_FMT),
                    succeeded = succeeded,
                    counts = it.liveCounts,
                ),
            )
        }
    }

    fun onPermissionsGranted() {
        _state.update { it.copy(hasPermissions = true, permissionRationale = null) }
    }

    fun onPermissionsDenied() {
        _state.update {
            it.copy(
                hasPermissions = false,
                permissionRationale = "Bluetooth permissions are required to connect to the watch.",
            )
        }
    }

    /** Scan for + connect/bind to the known watch. No-op without permissions. */
    fun connect() {
        if (!_state.value.hasPermissions) {
            onPermissionsDenied()
            return
        }
        engine.connect(deviceId)
    }

    fun disconnect() {
        engine.disconnect()
    }

    /** Trigger a health sync (only meaningful once connected). */
    fun syncNow() {
        engine.syncHealth()
    }

    /**
     * Push a sample notification to the watch face (W7) to verify the message path on-device.
     * Sets a transient [WatchHealthUiState.notificationHint] with the outcome.
     */
    fun sendTestNotification() {
        val dispatched = engine.sendNotification(
            WatchNotification(appName = "Dashboard", body = "Test notification from your dashboard."),
        )
        _state.update {
            it.copy(notificationHint = if (dispatched) "Notification sent to watch." else "Couldn't send — not connected.")
        }
    }

    /** Clear the transient notification feedback once shown. */
    fun clearNotificationHint() {
        _state.update { it.copy(notificationHint = null) }
    }

    override fun onCleared() {
        registerUiListener(null)
        registerUploadListener(null)
    }

    private companion object {
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
