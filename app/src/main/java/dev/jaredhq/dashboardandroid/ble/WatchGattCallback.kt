package dev.jaredhq.dashboardandroid.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build

/**
 * BluetoothGattCallback for the VeryFit/IDO BLE protocol.
 *
 * Handles connection lifecycle, service discovery, MTU negotiation,
 * notification enablement, battery reads, and raw packet logging.
 */
class WatchGattCallback(
    private val packetLogger: WatchPacketLogger,
    private val onStateChange: (WatchConnectionState) -> Unit,
    private val onMtuChanged: (Int) -> Unit,
    private val onBatteryRead: (Int) -> Unit,
    private val onMacResponse: (String) -> Unit,
) : BluetoothGattCallback() {

    var writeCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var notifyCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var secondaryNotifyCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var batteryCharacteristic: BluetoothGattCharacteristic? = null
        private set

    private var hasEnabledNotifications = false

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        packetLogger.log("GATT", "onConnectionStateChange status=$status newState=$newState")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onStateChange(WatchConnectionState.Connecting(gatt.device?.address ?: "unknown"))
                    // Request MTU 517 (max); watch typically negotiates to 247
                    try {
                        gatt.requestMtu(517)
                    } catch (e: SecurityException) {
                        packetLogger.log("GATT", "SecurityException requesting MTU: ${e.message}")
                    }
                } else {
                    onStateChange(WatchConnectionState.Error("Connection failed: status=$status"))
                    gatt.close()
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                onStateChange(WatchConnectionState.Disconnected)
                gatt.close()
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        packetLogger.log("GATT", "onMtuChanged mtu=$mtu status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            onMtuChanged(mtu)
        }
        // Proceed to service discovery regardless of MTU result
        try {
            gatt.discoverServices()
        } catch (e: SecurityException) {
            packetLogger.log("GATT", "SecurityException discovering services: ${e.message}")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        packetLogger.log("GATT", "onServicesDiscovered status=$status")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            onStateChange(WatchConnectionState.Error("Service discovery failed: status=$status"))
            return
        }

        val service = gatt.getService(WatchBleManager.VERYFIT_SERVICE_UUID)
        if (service == null) {
            packetLogger.log("GATT", "VeryFit service ${WatchBleManager.VERYFIT_SERVICE_UUID} not found")
            onStateChange(WatchConnectionState.Error("VeryFit service not found"))
            return
        }

        writeCharacteristic = service.getCharacteristic(WatchBleManager.WRITE_CHARACTERISTIC_UUID)
        notifyCharacteristic = service.getCharacteristic(WatchBleManager.NOTIFY_CHARACTERISTIC_UUID)
        secondaryNotifyCharacteristic = service.getCharacteristic(WatchBleManager.SECONDARY_NOTIFY_UUID)

        packetLogger.log("GATT", "Write char: ${writeCharacteristic?.uuid}")
        packetLogger.log("GATT", "Notify char: ${notifyCharacteristic?.uuid}")
        packetLogger.log("GATT", "Secondary notify char: ${secondaryNotifyCharacteristic?.uuid}")

        // Enable notifications on 0x0AF7
        notifyCharacteristic?.let { char ->
            enableNotification(gatt, char)
        }

        // Enable notifications on 0x0AF2 (if present)
        secondaryNotifyCharacteristic?.let { char ->
            enableNotification(gatt, char)
        }

        // Read battery from standard GATT 0x180F service
        val batteryService = gatt.getService(WatchBleManager.BATTERY_SERVICE_UUID)
        batteryCharacteristic = batteryService?.getCharacteristic(WatchBleManager.BATTERY_LEVEL_UUID)
        batteryCharacteristic?.let { char ->
            try {
                gatt.readCharacteristic(char)
            } catch (e: SecurityException) {
                packetLogger.log("GATT", "SecurityException reading battery: ${e.message}")
            }
        }

        // Transition to Connected state once everything is set up
        val device = gatt.device
        onStateChange(
            WatchConnectionState.Connected(
                deviceAddress = device?.address ?: "unknown",
                deviceName = device?.name,
            )
        )
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        packetLogger.log("RX", "${characteristic.uuid}: ${value.toHex()}")
        packetLogger.logRaw(WatchPacketLogger.DIRECTION_RX, characteristic.uuid.toString(), value)
        parseResponse(value)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            characteristic.value ?: byteArrayOf()
        } else {
            characteristic.value ?: byteArrayOf()
        }
        packetLogger.log("RX", "${characteristic.uuid}: ${value.toHex()}")
        packetLogger.logRaw(WatchPacketLogger.DIRECTION_RX, characteristic.uuid.toString(), value)
        parseResponse(value)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        packetLogger.log("RX-READ", "${characteristic.uuid}: ${value.toHex()} status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == WatchBleManager.BATTERY_LEVEL_UUID) {
            val battery = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            if (battery >= 0) onBatteryRead(battery)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        val value = characteristic.value ?: byteArrayOf()
        packetLogger.log("RX-READ", "${characteristic.uuid}: ${value.toHex()} status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == WatchBleManager.BATTERY_LEVEL_UUID) {
            val battery = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            if (battery >= 0) onBatteryRead(battery)
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        packetLogger.log("GATT", "onCharacteristicWrite ${characteristic.uuid} status=$status")
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        packetLogger.log("GATT", "onDescriptorWrite ${descriptor.uuid} status=$status")
        if (descriptor.uuid == WatchBleManager.CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
            hasEnabledNotifications = true
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            val success = gatt.setCharacteristicNotification(characteristic, true)
            packetLogger.log("GATT", "setCharacteristicNotification ${characteristic.uuid} success=$success")
            if (!success) return

            val descriptor = characteristic.getDescriptor(WatchBleManager.CCCD_UUID)
            if (descriptor != null) {
                val cccdValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, cccdValue)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = cccdValue
                    gatt.writeDescriptor(descriptor)
                }
                packetLogger.log("GATT", "Writing CCCD ENABLE_NOTIFICATION to ${characteristic.uuid}")
            } else {
                packetLogger.log("GATT", "No CCCD descriptor on ${characteristic.uuid}")
            }
        } catch (e: SecurityException) {
            packetLogger.log("GATT", "SecurityException enabling notification: ${e.message}")
        }
    }

    private fun parseResponse(value: ByteArray) {
        if (value.size < 4) return
        // VeryFit protocol: first byte often 0x02, second byte command type
        val cmdType = value.getOrNull(1)?.toInt()?.and(0xFF)
        when (cmdType) {
            0x04 -> {
                // MAC address response: 02:04 returns MAC twice
                val mac = WatchProtocol.parseMacAddressResponse(value)
                if (mac.isNotBlank()) {
                    packetLogger.log("PARSE", "MAC address: $mac")
                    onMacResponse(mac)
                }
            }
            0x02 -> {
                packetLogger.log("PARSE", "Device info response")
            }
            0x07 -> {
                packetLogger.log("PARSE", "Status response")
            }
            else -> {
                packetLogger.log("PARSE", "Unknown response type: 0x${cmdType?.toString(16)?.padStart(2, '0')}")
            }
        }
    }
}
