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
import android.os.ParcelUuid
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
            result?.device?.let { device ->
                stopScan()
                connect(device)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.firstOrNull()?.device?.let { device ->
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
     */
    val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
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
        packetLogger.log("BLE", "Starting scan for service ${VERYFIT_SERVICE_UUID}")
        isScanning = true

        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.update { WatchConnectionState.Error("BLE scanner not available") }
            isScanning = false
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(VERYFIT_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
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
        // A fresh connection attempt: clear the previous session's connect time so
        // the eventual Connected state stamps a new window.
        connectedAtMillis = null
        _state.update { WatchConnectionState.Connecting(device.address) }
        packetLogger.log("BLE", "Connecting to ${device.address}")

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
            onBatteryRead = { battery ->
                _state.update { current ->
                    if (current is WatchConnectionState.Connected) {
                        current.copy(batteryPercent = battery)
                    } else current
                }
            },
            onMacResponse = { mac ->
                lastMacAddress = mac
                _state.update { current ->
                    if (current is WatchConnectionState.Connected) {
                        current.copy(macAddress = mac)
                    } else current
                }
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
     * Write a raw command to the VeryFit write characteristic (0x0AF6).
     * The command bytes should include the full VeryFit packet (header + payload + checksum).
     */
    fun writeCommand(command: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val callback = gattCallback ?: return false
        val characteristic = callback.writeCharacteristic ?: return false

        packetLogger.logRaw(WatchPacketLogger.DIRECTION_TX, characteristic.uuid.toString(), command)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val status = gatt.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                status == BluetoothGatt.GATT_SUCCESS
            } catch (e: SecurityException) {
                false
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = command
            try {
                gatt.writeCharacteristic(characteristic)
            } catch (e: SecurityException) {
                false
            }
        }
    }

    /**
     * Send the 02:04 command to request the watch MAC address.
     */
    fun requestMacAddress() {
        val cmd = WatchProtocol.buildMacAddressCommand()
        packetLogger.log("TX", "02:04 MAC request: ${cmd.toHex()}")
        writeCommand(cmd)
    }

    /**
     * Send the 02:02 command (device info request).
     */
    fun requestDeviceInfo() {
        val cmd = WatchProtocol.buildDeviceInfoCommand()
        packetLogger.log("TX", "02:02 Device info: ${cmd.toHex()}")
        writeCommand(cmd)
    }

    /**
     * Send the 02:07 command (battery/status request).
     */
    fun requestStatus() {
        val cmd = WatchProtocol.buildStatusCommand()
        packetLogger.log("TX", "02:07 Status: ${cmd.toHex()}")
        writeCommand(cmd)
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

        /** Secondary notify characteristic: 0x0AF2 */
        val SECONDARY_NOTIFY_UUID: UUID = UUID.fromString("00000af2-0000-1000-8000-00805f9b34fb")

        /** CCCD descriptor: 0x2902 */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Battery service: 0x180F */
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

        /** Battery level characteristic: 0x2A19 */
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }
}

/** Convert a ByteArray to a hex string, space-separated. */
fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
