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
 * notification enablement, and response decoding.
 *
 * Inbound notifications are binary IDO V3 frames (see [WatchProtocol] for the
 * verified transport architecture). Every notification is logged with a full
 * structured breakdown so an on-device capture can confirm the exact framing;
 * battery is then extracted from the frame payload, with an ASCII-JSON fallback.
 */
class WatchGattCallback(
    private val packetLogger: WatchPacketLogger,
    private val onStateChange: (WatchConnectionState) -> Unit,
    private val onMtuChanged: (Int) -> Unit,
    private val onBatteryInfo: (WatchBatteryInfo) -> Unit,
    private val onMacResponse: (String) -> Unit,
    private val onResponseHex: (String) -> Unit = {},
    private val onCommandWriteComplete: (() -> Unit)? = null,
    private val onNotificationsEnabled: (() -> Unit)? = null,
) : BluetoothGattCallback() {

    var writeCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var notifyCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var secondaryNotifyCharacteristic: BluetoothGattCharacteristic? = null
        private set
    var extraEncryptCharacteristic: BluetoothGattCharacteristic? = null
        private set

    private var hasEnabledNotifications = false
    // MTU negotiated before service discovery; carried into the initial Connected state.
    private var negotiatedMtu = 23

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        val bondState = try {
            gatt.device?.bondState
        } catch (_: SecurityException) {
            null
        }
        packetLogger.log("GATT", "onConnectionStateChange status=$status newState=$newState bond=$bondState")
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
            negotiatedMtu = mtu
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
            gatt.services.forEach { discovered ->
                packetLogger.log("GATT", "Discovered service ${discovered.uuid}")
                discovered.characteristics.forEach { ch ->
                    packetLogger.log("GATT", "  char ${ch.uuid} props=0x${ch.properties.toString(16)}")
                }
            }
            onStateChange(WatchConnectionState.Error("VeryFit service not found"))
            return
        }

        writeCharacteristic = service.getCharacteristic(WatchBleManager.WRITE_CHARACTERISTIC_UUID)
        notifyCharacteristic = service.getCharacteristic(WatchBleManager.NOTIFY_CHARACTERISTIC_UUID)
        secondaryNotifyCharacteristic = service.getCharacteristic(WatchBleManager.SECONDARY_NOTIFY_UUID)
        extraEncryptCharacteristic = service.getCharacteristic(WatchBleManager.EXTRA_ENCRYPT_UUID)

        packetLogger.log("GATT", "Write char: ${writeCharacteristic?.uuid}")
        packetLogger.log("GATT", "Notify char: ${notifyCharacteristic?.uuid}")
        packetLogger.log("GATT", "Secondary notify char: ${secondaryNotifyCharacteristic?.uuid}")
        packetLogger.log("GATT", "Extra/encrypt char: ${extraEncryptCharacteristic?.uuid}")
        service.characteristics.forEach { ch ->
            packetLogger.log("GATT", "Service char ${ch.uuid} props=0x${ch.properties.toString(16)}")
        }

        // Transition to Connected immediately so the UI is responsive; battery and
        // MAC come in as subsequent state updates once the GATT op chain below finishes.
        val device = gatt.device
        onStateChange(
            WatchConnectionState.Connected(
                deviceAddress = device?.address ?: "unknown",
                deviceName = device?.name,
                mtu = negotiatedMtu,
            )
        )

        // VeryFit firmware can expose 0x0AF8 as an encrypt/background-control
        // characteristic. The official app reads it before enabling notify when
        // present; after firmware updates this can become the difference between a
        // quiet connection and a usable one. Treat read failure as diagnostic, then
        // still fall through to notification setup.
        extraEncryptCharacteristic?.takeIf {
            it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        }?.let { ch ->
            try {
                packetLogger.log("GATT", "Reading 0x0AF8 encrypt/background characteristic before notifications")
                if (gatt.readCharacteristic(ch)) return
                packetLogger.log("GATT", "readCharacteristic 0x0AF8 returned false; continuing to notifications")
            } catch (e: SecurityException) {
                packetLogger.log("GATT", "SecurityException reading 0x0AF8: ${e.message}")
            }
        }

        startNotificationEnablement(gatt)
    }

    private fun startNotificationEnablement(gatt: BluetoothGatt) {
        // Serialize GATT ops: AF7 CCCD → AF2 CCCD.
        // Android GATT has no internal queue; overlapping writes silently fail.
        // onDescriptorWrite chains each step only after the previous one completes.
        notifyCharacteristic?.let { enableNotification(gatt, it) }
            ?: secondaryNotifyCharacteristic?.let { enableNotification(gatt, it) }
            ?: onNotificationsEnabled?.invoke()
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        packetLogger.log("GATT", "onCharacteristicRead ${characteristic.uuid} status=$status value=${value.toHex()}")
        if (characteristic.uuid == WatchBleManager.EXTRA_ENCRYPT_UUID) startNotificationEnablement(gatt)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        val value = characteristic.value ?: byteArrayOf()
        packetLogger.log("GATT", "onCharacteristicRead ${characteristic.uuid} status=$status value=${value.toHex()}")
        if (characteristic.uuid == WatchBleManager.EXTRA_ENCRYPT_UUID) startNotificationEnablement(gatt)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        packetLogger.log("RX", "${characteristic.uuid}: ${value.toHex()}")
        packetLogger.logRaw(WatchPacketLogger.DIRECTION_RX, characteristic.uuid.toString(), value)
        onResponseHex(value.toHex())
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
        onResponseHex(value.toHex())
        parseResponse(value)
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        packetLogger.log("GATT", "onCharacteristicWrite ${characteristic.uuid} status=$status")
        onCommandWriteComplete?.invoke()
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        packetLogger.log("GATT", "onDescriptorWrite ${descriptor.uuid} status=$status")
        if (descriptor.uuid == WatchBleManager.CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
            hasEnabledNotifications = true
            // Chain to the next serialized op now that the previous one has completed.
            when (descriptor.characteristic.uuid) {
                WatchBleManager.NOTIFY_CHARACTERISTIC_UUID ->
                    secondaryNotifyCharacteristic?.let { enableNotification(gatt, it) }
                        ?: onNotificationsEnabled?.invoke()
                WatchBleManager.SECONDARY_NOTIFY_UUID -> onNotificationsEnabled?.invoke()
            }
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

    /**
     * Decode an incoming notification.
     *
     * Order of operations is observability-first: we always emit a structured frame
     * breakdown (head/cmd/key/len/payload/CRC) so a single on-device capture confirms
     * or corrects the still-unverified IDO V3 framing. We then try to surface battery
     * from (1) the binary frame payload and (2) an ASCII-JSON fallback, updating the
     * UI whenever either succeeds.
     */
    private fun parseResponse(value: ByteArray) {
        if (value.isEmpty()) return

        // 1) Defensive ASCII-JSON path (rare on this transport, but cheap and harmless).
        if (WatchProtocol.looksLikeJson(value)) {
            WatchProtocol.parseBatteryInfoFromJson(value)?.let {
                packetLogger.log("PARSE", "Battery (JSON): level=${it.level}% status=${it.status} voltage=${it.voltage}mV")
                onBatteryInfo(it)
                return
            }
            WatchProtocol.parseMacAddressFromJson(value)?.let {
                packetLogger.log("PARSE", "MAC (JSON): $it")
                onMacResponse(it)
                return
            }
            packetLogger.log("PARSE", "JSON notification (unmapped): ${value.toString(Charsets.UTF_8)}")
            return
        }

        // 2) Binary IDO V3 frame path. Log the structured breakdown first.
        val frame = WatchProtocol.parseFrame(value)
        if (frame == null) {
            packetLogger.log("PARSE", "Short/non-frame notification (needs capture): ${value.toHex()}")
            return
        }
        packetLogger.log("FRAME", frame.summary())
        if (!frame.crcValid) {
            // Not an error — our framing constants are still hypotheses. Logged so a
            // capture reveals the real head byte / CRC variant / offsets.
            packetLogger.log("FRAME", "CRC mismatch: framing hypothesis unconfirmed — capture needed to lock head/cmd/key/CRC")
        }

        // 3) Best-effort battery from the binary payload (and a whole-frame fallback in
        //    case the true payload offset differs from our hypothesis).
        val battery = WatchProtocol.parseBatteryInfoFromBinary(frame.payload)
            ?: WatchProtocol.parseBatteryInfoFromBinary(value)
        if (battery != null) {
            val confidence = if (frame.crcValid) "verified-frame" else "UNVERIFIED-heuristic"
            packetLogger.log("PARSE", "Battery ($confidence): level=${battery.level}% status=${battery.status} voltage=${battery.voltage}mV")
            onBatteryInfo(battery)
            return
        }

        packetLogger.log("PARSE", "Frame not recognised as battery (cmd=0x%02X key=0x%02X)".format(frame.cmd, frame.key))
    }
}
