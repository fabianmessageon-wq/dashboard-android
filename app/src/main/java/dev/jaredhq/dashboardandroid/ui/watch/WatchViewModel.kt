package dev.jaredhq.dashboardandroid.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jaredhq.dashboardandroid.ble.WatchBleManager
import dev.jaredhq.dashboardandroid.ble.WatchConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Watch screen — a single immutable snapshot the screen renders.
 */
data class WatchUiState(
    val state: WatchConnectionState = WatchConnectionState.Disconnected,
    val rawLog: String = "",
    val hasPermissions: Boolean = false,
    val permissionRationale: String? = null,
    /** Transient confirmation shown after the user queues a dashboard sync. */
    val syncMessage: String? = null,
)

/**
 * Drives the Watch (BLE) screen. Manages scan, connect, disconnect, and command
 * actions. State is exposed as a [StateFlow] for Compose consumption.
 */
class WatchViewModel(
    private val bleManager: WatchBleManager,
) : ViewModel() {

    private val _state = MutableStateFlow(WatchUiState())
    val state: StateFlow<WatchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            bleManager.state.collect { bleState ->
                _state.update {
                    it.copy(
                        state = bleState,
                        rawLog = bleManager.logger.format(),
                    )
                }
            }
        }
    }

    fun onPermissionsGranted() {
        _state.update { it.copy(hasPermissions = true, permissionRationale = null) }
    }

    fun onPermissionsDenied() {
        _state.update {
            it.copy(
                hasPermissions = false,
                permissionRationale = "BLE permissions are required to scan and connect to the watch.",
            )
        }
    }

    fun startScan() {
        if (!bleManager.isReady()) {
            _state.update { it.copy(state = WatchConnectionState.Error("Bluetooth is disabled")) }
            return
        }
        bleManager.startScan()
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun requestMacAddress() {
        bleManager.requestMacAddress()
        _state.update { it.copy(rawLog = bleManager.logger.format()) }
    }

    fun requestDeviceInfo() {
        bleManager.requestDeviceInfo()
        _state.update { it.copy(rawLog = bleManager.logger.format()) }
    }

    fun requestBatteryInfo() {
        bleManager.requestBatteryInfo()
        _state.update { it.copy(rawLog = bleManager.logger.format()) }
    }

    /**
     * Note that a one-off telemetry sync has been queued. The actual enqueue runs
     * via WatchSyncScheduler (it needs a Context, so the screen triggers it); this
     * just surfaces a brief confirmation in the UI.
     */
    fun onSyncRequested() {
        _state.update { it.copy(syncMessage = "Telemetry sync queued") }
    }

    fun clearLog() {
        bleManager.logger.clear()
        _state.update { it.copy(rawLog = "") }
    }

    fun refreshLog() {
        _state.update { it.copy(rawLog = bleManager.logger.format()) }
    }
}
