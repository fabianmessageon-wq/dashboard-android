package dev.jaredhq.dashboardandroid.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * High-level BLE manager for the Kogan Active 4 Pro (VeryFit/IDO protocol).
 *
 * Handles scan, connect, disconnect, and delegates GATT events to [WatchGattCallback].
 * All state is exposed as a [StateFlow] of [WatchConnectionState] for Compose consumption.
 *
 * Commands are binary IDO V3 frames built by [WatchProtocol] (the official app's
 * native lib frames JSON into these binary packets before writing them to BLE — the
 * wire never carries JSON). The exact battery cmd/key and CRC are still unverified;
 * see [WatchProtocol] for the capture-driven path to confirming them.
 */
class WatchBleManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _state = MutableStateFlow<WatchConnectionState>(WatchConnectionState.Disconnected)
    val state: StateFlow<WatchConnectionState> = _state.asStateFlow()

    private val packetLogger = WatchPacketLogger()
    val logger: WatchPacketLogger get() = packetLogger

    private var bluetoothGatt: BluetoothGatt? = null
    private var gattCallback: WatchGattCallback? = null
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCommands = ArrayDeque<ByteArray>()
    private var writeInProgress = false

    /** Epoch millis the current connection became [WatchConnectionState.Connected], or null. */
    @Volatile
    var connectedAtMillis: Long? = null
        private set

    /** Epoch millis of the most recent disconnect, or null if never disconnected. */
    @Volatile
    var disconnectedAtMillis: Long? = null
        private set

    // Last known device identity, retained across a disconnect so a disconnect sync
    // can still name the device for the dashboard's connection history.
    @Volatile
    private var lastDeviceAddress: String? = null
    @Volatile
    private var lastDeviceName: String? = null
    @Volatile
    private var lastMacAddress: String? = null

    /**
     * Invoked whenever the connection reaches a terminal/meaningful state
     * (connected or disconnected) so the app can enqueue a one-off telemetry sync.
     * Set by [dev.jaredhq.dashboardandroid.di.ServiceLocator]; left null in tests.
     */
    var onConnectionEvent: (() -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val match = result?.takeIf { it.matchesTargetWatch() } ?: run {
                result?.deviceNameForLog()?.let { packetLogger.log("SCAN", "Ignoring nearby BLE device: $it") }
                return
            }
            match.device?.let { device ->
                stopScan()
                connect(device)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.firstOrNull { it.matchesTargetWatch() }?.device?.let { device ->
                stopScan()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _state.update {
                WatchConnectionState.Error("Scan failed: errorCode=$errorCode")
            }
        }
    }

    /**
     * Required BLE permissions for Android 12+ (API 31) and below.
     *
     * On API 31+, BLUETOOTH_SCAN is declared with neverForLocation in the manifest,
     * so location permission is not required for scanning.
     */
    val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    /**
     * Returns true if Bluetooth is enabled and permissions are available.
     * The caller should still check runtime permissions before calling [startScan].
     */
    fun isReady(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Start scanning for the VeryFit service (0x0AF0).
     * Stops automatically when the first matching device is found.
     */
    fun startScan() {
        val adapter = bluetoothAdapter ?: run {
            _state.update { WatchConnectionState.Error("Bluetooth not available") }
            return
        }
        if (isScanning) return

        _state.update { WatchConnectionState.Scanning }
        packetLogger.clear()
        packetLogger.log("BLE", "Starting scan for Active/Kogan/VeryFit watch (service ${VERYFIT_SERVICE_UUID} when advertised)")
        isScanning = true

        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.update { WatchConnectionState.Error("BLE scanner not available") }
            isScanning = false
            return
        }

        // Do not filter only by service UUID. Some IDO/VeryFit watches do not put
        // 0x0AF0 in the advertising packet even though the service appears after
        // connect/service-discovery; a service-only scan then never finds the watch.
        // Scan broadly and connect only when the advertisement name or UUID matches.
        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            mainHandler.postDelayed({
                if (isScanning) {
                    stopScan()
                    _state.update { WatchConnectionState.Error("Watch not found after ${SCAN_TIMEOUT_MS / 1000}s. Keep it nearby/awake, make sure VeryFit is closed, then retry.") }
                }
            }, SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            isScanning = false
            _state.update { WatchConnectionState.Error("Missing BLE permissions: ${e.message}") }
        }
    }

    /**
     * Stop an active scan.
     */
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        mainHandler.removeCallbacksAndMessages(null)
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    /**
     * Connect to a specific device. Usually called internally from [scanCallback],
     * but exposed for direct connection (e.g. reconnect to last known device).
     */
    fun connect(device: BluetoothDevice) {
        stopScan()
        pendingCommands.clear()
        writeInProgress = false
        // A fresh connection attempt: clear the previous session's connect time so
        // the eventual Connected state stamps a new window.
        connectedAtMillis = null
        _state.update { WatchConnectionState.Connecting(device.address) }
        val bondState = try {
            device.bondState
        } catch (_: SecurityException) {
            null
        }
        packetLogger.log("BLE", "Connecting to ${device.address} bond=$bondState")
        if (bondState == BluetoothDevice.BOND_NONE) {
            try {
                packetLogger.log("BLE", "Device is not bonded; requesting bond before/alongside GATT connect")
                packetLogger.log("BLE", "createBond result=${device.createBond()}")
            } catch (e: SecurityException) {
                packetLogger.log("BLE", "SecurityException creating bond: ${e.message}")
            }
        }

        gattCallback = WatchGattCallback(
            packetLogger = packetLogger,
            onStateChange = { newState -> applyState(newState) },
            onMtuChanged = { mtu ->
                _state.update { current ->
                    if (current is WatchConnectionState.Connected) {
                        current.copy(mtu = mtu)
                    } else current
                }
            },
            onBatteryInfo = { batteryInfo ->
                _state.update { current ->
                    if (current is WatchConnectionState.Connected) {
                        current.copy(
                            batteryInfo = batteryInfo,
                            batteryPercent = batteryInfo.level,
                        )
                    } else current
                }
                // Battery arrives after the initial Connected event, so schedule a
                // fresh upload with the enriched telemetry instead of leaving the
                // dashboard with the early, empty connection snapshot.
                onConnectionEvent?.invoke()
            },
            onMacResponse = { mac ->
                lastMacAddress = mac
                _state.update { current ->
                    if (current is WatchConnectionState.Connected) {
                        current.copy(macAddress = mac)
                    } else current
                }
                onConnectionEvent?.invoke()
            },
            onResponseHex = { hex ->
                _state.update { current ->
                    if (current is WatchConnectionState.Connected) current.copy(lastResponseHex = hex)
                    else current
                }
                // Raw RX packets are developer-visible data the dashboard endpoint
                // is meant to receive; the initial connection sync fires too early
                // to include them, so coalesce another one-off upload here.
                onConnectionEvent?.invoke()
            },
            onCommandWriteComplete = {
                writeInProgress = false
                drainCommandQueue()
                if (!writeInProgress && pendingCommands.isEmpty()) onConnectionEvent?.invoke()
            },
            onNotificationsEnabled = {
                // Official-app btsnoop showed the first useful post-CCCD probe is the
                // short 0x02 0x01 request; the previous guessed AB frames only yielded
                // ACKs. Keep legacy buttons manual, but auto-probe with captured bytes.
                packetLogger.log("BLE", "Notifications enabled, queueing captured 02:01 status probe burst")
                requestCapturedStatusProbeBurst()
            },
        )

        try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback!!, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback!!)
            }
        } catch (e: SecurityException) {
            _state.update { WatchConnectionState.Error("Missing BLE_CONNECT permission: ${e.message}") }
        }
    }

    /**
     * Disconnect and release the GATT connection.
     */
    fun disconnect() {
        stopScan()
        try {
            bluetoothGatt?.disconnect()
        } catch (_: SecurityException) {
        }
        // onConnectionStateChange will trigger close()
    }

    /**
     * Write a binary IDO V3 command frame to the VeryFit write characteristic
     * (0x0AF6). Frames are built by [WatchProtocol]; this method is transport-only.
     */
    fun writeCommand(command: ByteArray): Boolean {
        if (_state.value !is WatchConnectionState.Connected) return false
        if (bluetoothGatt == null || gattCallback?.writeCharacteristic == null) return false
        pendingCommands.addLast(command)
        drainCommandQueue()
        return true
    }

    private fun drainCommandQueue() {
        if (writeInProgress) return
        val command = pendingCommands.removeFirstOrNull() ?: return
        val gatt = bluetoothGatt ?: return
        val callback = gattCallback ?: return
        val characteristic = callback.writeCharacteristic ?: return

        packetLogger.logRaw(WatchPacketLogger.DIRECTION_TX, characteristic.uuid.toString(), command)
        _state.update { current ->
            if (current is WatchConnectionState.Connected) current.copy(lastCommandHex = command.toHex())
            else current
        }

        writeInProgress = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val status = gatt.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    writeInProgress = false
                    packetLogger.log("GATT", "writeCharacteristic failed immediately status=$status")
                    drainCommandQueue()
                }
            } catch (e: SecurityException) {
                writeInProgress = false
                packetLogger.log("GATT", "SecurityException writing characteristic: ${e.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = command
            try {
                if (!gatt.writeCharacteristic(characteristic)) {
                    writeInProgress = false
                    packetLogger.log("GATT", "writeCharacteristic returned false")
                    drainCommandQueue()
                }
            } catch (e: SecurityException) {
                writeInProgress = false
                packetLogger.log("GATT", "SecurityException writing characteristic: ${e.message}")
            }
        }
    }

    /**
     * Request the watch MAC address via capture-verified command `02 04`.
     * Response: `02 04 <mac 6 bytes> <mac 6 bytes>`.
     */
    fun requestMacAddress() {
        val cmd = WatchProtocol.buildMacAddressCommand()
        packetLogger.log("TX", "MAC_ADDRESS req (02:04, watch-verified): ${cmd.toHex()}")
        writeCommand(cmd)
    }

    /**
     * Request basic device info via capture-observed command `02 02`.
     */
    fun requestDeviceInfo() {
        val cmd = WatchProtocol.buildDeviceInfoCommand()
        packetLogger.log("TX", "DEVICE_INFO req (02:02, capture-observed): ${cmd.toHex()}")
        writeCommand(cmd)
    }

    /**
     * Request battery info via capture-verified command `02 A7`.
     * Response: `02 A7 xx xx xx <level%> ...`; byte[5] = battery %.
     */
    fun requestBatteryInfo() {
        val cmd = WatchProtocol.buildBatteryInfoCommand()
        packetLogger.log("TX", "BATTERY req (02:A7, watch-verified): ${cmd.toHex()}")
        writeCommand(cmd)
    }

    /**
     * Send command 348 (0x015C) to request firmware status info.
     */
    fun requestFirmwareStatus() {
        val cmd = WatchProtocol.buildFirmwareStatusCommand()
        packetLogger.log("TX", "CMD_GET_FIRMWARE_STATUS (348): ${cmd.toHex()}")
        writeCommand(cmd)
    }

    /** Send the official-capture status probe: WRITE 0x0AF6 `02 01`. */
    fun requestCapturedStatusProbe() {
        val cmd = WatchProtocol.buildCapturedStatusProbeCommand()
        packetLogger.log("TX", "CAPTURED_STATUS_PROBE (02:01): ${cmd.toHex()}")
        writeCommand(cmd)
    }

    /**
     * Post-CCCD probe sequence using capture-verified commands. Fires immediately
     * and then retries the status probe twice, matching the pattern observed in the
     * official-app btsnoop (MAC → device info → status probe, spaced out).
     */
    private fun requestCapturedStatusProbeBurst() {
        // t=0ms: MAC address (02:04, watch-verified)
        mainHandler.postDelayed({
            if (_state.value is WatchConnectionState.Connected && bluetoothGatt != null) {
                packetLogger.log("BLE", "Post-CCCD: MAC probe (02:04)")
                requestMacAddress()
            }
        }, 0L)
        // t=400ms: status/battery probe (02:01, watch-verified)
        mainHandler.postDelayed({
            if (_state.value is WatchConnectionState.Connected && bluetoothGatt != null) {
                packetLogger.log("BLE", "Post-CCCD: status probe (02:01) attempt 1")
                requestCapturedStatusProbe()
            }
        }, 400L)
        // t=1000ms: battery poll (02:A7, watch-verified)
        mainHandler.postDelayed({
            if (_state.value is WatchConnectionState.Connected && bluetoothGatt != null) {
                packetLogger.log("BLE", "Post-CCCD: battery poll (02:A7)")
                requestBatteryInfo()
            }
        }, 1_000L)
        // t=2500ms: retry status probe if battery not yet resolved
        mainHandler.postDelayed({
            if (_state.value is WatchConnectionState.Connected && bluetoothGatt != null) {
                packetLogger.log("BLE", "Post-CCCD: status probe (02:01) attempt 2")
                requestCapturedStatusProbe()
            }
        }, 2_500L)
    }

    /** Send an exact tester/capture-provided command frame to 0x0AF6. */
    fun sendDebugHexCommand(hex: String): Boolean {
        val cmd = WatchProtocol.parseHexCommand(hex) ?: run {
            packetLogger.log("TX", "Invalid raw hex command: $hex")
            return false
        }
        packetLogger.log("TX", "RAW_HEX debug command: ${cmd.toHex()}")
        return writeCommand(cmd)
    }

    /**
     * Centralized lifecycle-state apply: records connect/disconnect timestamps and
     * notifies [onConnectionEvent] so the app can sync telemetry. Only the GATT
     * lifecycle transitions flow through here; battery/MTU/MAC refinements update
     * the [Connected][WatchConnectionState.Connected] state directly and must not
     * re-trigger a sync.
     */
    private fun applyState(newState: WatchConnectionState) {
        when (newState) {
            is WatchConnectionState.Connected -> {
                if (connectedAtMillis == null) connectedAtMillis = System.currentTimeMillis()
                lastDeviceAddress = newState.deviceAddress
                newState.deviceName?.let { lastDeviceName = it }
            }
            is WatchConnectionState.Disconnected,
            is WatchConnectionState.Error ->
                // Keep connectedAtMillis so a disconnect sync can report the full
                // session window; connect() resets it for the next attempt.
                disconnectedAtMillis = System.currentTimeMillis()
            else -> Unit // Scanning / Connecting: no timestamp change
        }
        _state.update { newState }
        when (newState) {
            is WatchConnectionState.Connected,
            is WatchConnectionState.Disconnected,
            is WatchConnectionState.Error -> onConnectionEvent?.invoke()
            else -> Unit
        }
    }

    /**
     * Build the current telemetry snapshot for a dashboard sync, or null when
     * there is no identifiable device yet (e.g. idle before the first connect).
     * Pure mapping lives in [WatchSyncMapper] so it stays unit-testable.
     */
    fun buildSyncRequest() = WatchSyncMapper.build(
        state = _state.value,
        connectedAtMillis = connectedAtMillis,
        disconnectedAtMillis = disconnectedAtMillis,
        lastDeviceAddress = lastDeviceAddress,
        lastDeviceName = lastDeviceName,
        lastMacAddress = lastMacAddress,
        rawEvents = packetLogger.getRawEvents(),
    )

    internal fun closeGatt() {
        try {
            bluetoothGatt?.close()
        } catch (_: SecurityException) {
        }
        bluetoothGatt = null
        gattCallback = null
    }

    companion object {
        /** VeryFit primary service: 0x0AF0 */
        val VERYFIT_SERVICE_UUID: UUID = UUID.fromString("00000af0-0000-1000-8000-00805f9b34fb")

        /** Write characteristic: 0x0AF6 */
        val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("00000af6-0000-1000-8000-00805f9b34fb")

        /** Notify characteristic: 0x0AF7 */
        val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("00000af7-0000-1000-8000-00805f9b34fb")

        /** Auxiliary notify characteristic observed after firmware update: 0x0AF1 */
        val AUX_NOTIFY_UUID: UUID = UUID.fromString("00000af1-0000-1000-8000-00805f9b34fb")

        /** Secondary notify characteristic: 0x0AF2 */
        val SECONDARY_NOTIFY_UUID: UUID = UUID.fromString("00000af2-0000-1000-8000-00805f9b34fb")

        /** Extra/encryption/background-control characteristic: 0x0AF8 */
        val EXTRA_ENCRYPT_UUID: UUID = UUID.fromString("00000af8-0000-1000-8000-00805f9b34fb")

        /** CCCD descriptor: 0x2902 */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 20_000L

        /**
         * Static BLE address of the Kogan Active 4 Pro, confirmed from btsnoop capture.
         * The `02 04` MAC response also returns `F4:91:29:51:C6:45` — watch-verified.
         * Used as a hard MAC fallback so the app connects even if the advertising name
         * doesn't match any keyword pattern.
         */
        const val KNOWN_WATCH_MAC = "F4:91:29:51:C6:45"
    }
}

private fun ScanResult.matchesTargetWatch(): Boolean {
    val advertisedServices = scanRecord?.serviceUuids.orEmpty().map { it.uuid }
    if (WatchBleManager.VERYFIT_SERVICE_UUID in advertisedServices) return true

    // Fallback: match by the btsnoop-verified static BLE address.
    val address = try { device?.address } catch (_: SecurityException) { null }
    if (address?.equals(WatchBleManager.KNOWN_WATCH_MAC, ignoreCase = true) == true) return true

    val name = scanRecord?.deviceName ?: try {
        device?.name
    } catch (_: SecurityException) {
        null
    }
    val normalized = name?.lowercase().orEmpty()
    return normalized.contains("active 4") ||
        normalized.contains("active") ||
        normalized.contains("kogan") ||
        normalized.contains("kaa4") ||
        normalized.contains("veryfit") ||
        normalized.contains("idw") ||
        normalized.contains("ido")
}

private fun ScanResult.deviceNameForLog(): String? {
    val name = scanRecord?.deviceName ?: try {
        device?.name
    } catch (_: SecurityException) {
        null
    }
    val address = try {
        device?.address
    } catch (_: SecurityException) {
        null
    }
    return name?.takeIf { it.isNotBlank() } ?: address
}

/** Convert a ByteArray to a hex string, space-separated. */
fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
