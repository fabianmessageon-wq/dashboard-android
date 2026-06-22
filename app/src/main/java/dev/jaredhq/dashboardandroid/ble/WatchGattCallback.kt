package dev.jaredhq.dashboardandroid.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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
    var auxiliaryNotifyCharacteristic: BluetoothGattCharacteristic? = null
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
                        packetLogger.log("GATT", "requestMtu(517) start")
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
        auxiliaryNotifyCharacteristic = service.getCharacteristic(WatchBleManager.AUX_NOTIFY_UUID)
        extraEncryptCharacteristic = service.getCharacteristic(WatchBleManager.EXTRA_ENCRYPT_UUID)

        packetLogger.log("GATT", "Write char: ${writeCharacteristic?.uuid}")
        packetLogger.log("GATT", "Notify char: ${notifyCharacteristic?.uuid}")
        packetLogger.log("GATT", "Secondary notify char: ${secondaryNotifyCharacteristic?.uuid}")
        packetLogger.log("GATT", "Auxiliary notify char: ${auxiliaryNotifyCharacteristic?.uuid}")
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
        // Serialize GATT ops: AF7 CCCD → AF2 CCCD → AF1 CCCD.
        // Firmware observed after the VeryFit update exposes 0x0AF1 as a notify
        // characteristic (props=0x10). Enable it too; battery/decoded data may be
        // routed there even when AF7 only emits short ACK/status packets.
        // Android GATT has no internal queue; overlapping writes silently fail.
        // onDescriptorWrite chains each step only after the previous one completes.
        nextNotifiableAfter(null)?.let { enableNotification(gatt, it) }
            ?: onNotificationsEnabled?.invoke()
    }

    private fun nextNotifiableAfter(previous: BluetoothGattCharacteristic?): BluetoothGattCharacteristic? {
        val ordered = listOfNotNull(
            notifyCharacteristic,
            secondaryNotifyCharacteristic,
            auxiliaryNotifyCharacteristic,
        )
        val startIndex = previous?.let { prev -> ordered.indexOfFirst { it.uuid == prev.uuid } + 1 } ?: 0
        return ordered.drop(startIndex.coerceAtLeast(0)).firstOrNull { it.isNotifiable() }
    }

    private fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

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
        val value = characteristic.value ?: byteArrayOf()
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
        if (descriptor.uuid == WatchBleManager.CCCD_UUID) {
            if (status == BluetoothGatt.GATT_SUCCESS) hasEnabledNotifications = true
            else packetLogger.log("GATT", "CCCD write failed for ${descriptor.characteristic.uuid}; continuing notification chain")

            // Chain to the next serialized op now that the previous one completed.
            chainAfter(gatt, descriptor.characteristic)
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            val success = gatt.setCharacteristicNotification(characteristic, true)
            packetLogger.log("GATT", "setCharacteristicNotification ${characteristic.uuid} success=$success")
            // Do NOT stall the chain if local enablement fails — log it and move on so a
            // single bad characteristic never blocks the remaining notify setup.
            if (!success) {
                packetLogger.log("GATT", "setCharacteristicNotification failed for ${characteristic.uuid}; continuing notification chain")
                return chainAfter(gatt, characteristic)
            }

            val descriptor = characteristic.getDescriptor(WatchBleManager.CCCD_UUID)
            if (descriptor == null) {
                packetLogger.log("GATT", "No CCCD descriptor on ${characteristic.uuid}; continuing notification chain")
                return chainAfter(gatt, characteristic)
            }

            // Honour the characteristic's own properties: an indicate-only characteristic
            // must be enabled with ENABLE_INDICATION_VALUE, not the notification value, or
            // the watch never sends data on it.
            val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            val cccdValue = if (indicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val mode = if (indicate) "INDICATION" else "NOTIFICATION"
            packetLogger.log("GATT", "Writing CCCD ENABLE_$mode to ${characteristic.uuid} start")

            val dispatched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The API 33 overload returns a BluetoothStatusCodes value, not a GATT status.
                gatt.writeDescriptor(descriptor, cccdValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = cccdValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            // If the write didn't even dispatch, onDescriptorWrite will never fire — chain
            // now so the setup can't hang waiting on a callback that isn't coming.
            if (!dispatched) {
                packetLogger.log("GATT", "writeDescriptor did not dispatch for ${characteristic.uuid}; continuing notification chain")
                chainAfter(gatt, characteristic)
            }
        } catch (e: SecurityException) {
            packetLogger.log("GATT", "SecurityException enabling notification: ${e.message}")
            chainAfter(gatt, characteristic)
        }
    }

    /** Advance the serialized notify-setup chain, or signal completion when none remain. */
    private fun chainAfter(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        nextNotifiableAfter(characteristic)?.let { enableNotification(gatt, it) }
            ?: onNotificationsEnabled?.invoke()
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

        // 2) Capture-verified response paths (watch-verified from btsnoop).

        // 02:01 basic-info response — full struct (deviceId/firmware/battery/...).
        // firmwareVersion here is the BASIC-INFO firmware byte (offset 4, commonly 1);
        // it is NOT the activity-data version and must never gate the BLE flow.
        WatchProtocol.parseBasicInfo(value)?.let { info ->
            packetLogger.log(
                "PARSE",
                "Basic info (02:01, watch-verified): deviceId=${info.deviceId} fw=${info.firmwareVersion} " +
                    "battery=${info.batteryLevel}% battStatus=${info.batteryStatus} mode=${info.mode} " +
                    "platform=${info.platform} devType=${info.devType}",
            )
            WatchProtocol.batteryFromBasicInfo(info)?.let { battery ->
                packetLogger.log("PARSE", "Battery (from 02:01 basic info): level=${battery.level}%")
                onBatteryInfo(battery)
            }
            return
        }
        // 02:A7 battery poll response — byte[5] = battery %.
        WatchProtocol.parseBatteryInfoFromCapturedBatteryPoll(value)?.let {
            packetLogger.log("PARSE", "Battery (02:A7 poll, watch-verified): level=${it.level}%")
            onBatteryInfo(it)
            return
        }
        // 02:04 MAC address response — bytes[2..7] = MAC.
        WatchProtocol.parseMacAddressFromCapturedMacResponse(value)?.let {
            packetLogger.log("PARSE", "MAC (02:04 response, watch-verified): $it")
            onMacResponse(it)
            return
        }
        if (WatchProtocol.looksLikeCapturedDaAdFrame(value)) {
            packetLogger.log("FRAME", WatchProtocol.describeCapturedDaAdFrame(value))
            return
        }

        // 3) Binary IDO V3 frame path. Log the structured breakdown first.
        val frame = WatchProtocol.parseFrame(value)
        if (frame == null) {
            packetLogger.log("PARSE", WatchProtocol.describeShortStatus(value))
            return
        }
        packetLogger.log("FRAME", frame.summary())
        if (!frame.crcValid) {
            // Not an error — our framing constants are still hypotheses. Logged so a
            // capture reveals the real head byte / CRC variant / offsets.
            packetLogger.log("FRAME", "CRC mismatch: framing hypothesis unconfirmed — capture needed to lock head/cmd/key/CRC")
        }

        // 3) Best-effort battery from the binary payload — ONLY when the frame's CRC
        //    actually validates (i.e. our framing hypothesis is confirmed for this
        //    frame). Real on-device traffic (verified 2026-06-22) interleaves short
        //    transport/ACK frames like `07 40 1F 00…` whose first byte (0x07) the
        //    unverified heuristic would otherwise misread as "7% battery", corrupting
        //    the correct 90% reading from the watch-verified 02:01/02:A7 paths above.
        //    Battery is now sourced from those verified paths; this heuristic must not
        //    push a value it cannot stand behind.
        if (frame.crcValid) {
            val battery = WatchProtocol.parseBatteryInfoFromBinary(frame.payload)
                ?: WatchProtocol.parseBatteryInfoFromBinary(value)
            if (battery != null) {
                packetLogger.log("PARSE", "Battery (verified-frame): level=${battery.level}% status=${battery.status} voltage=${battery.voltage}mV")
                onBatteryInfo(battery)
                return
            }
        }

        packetLogger.log("PARSE", "Frame not recognised as battery (cmd=0x%02X key=0x%02X, crcValid=${frame.crcValid})".format(frame.cmd, frame.key))
    }
}
