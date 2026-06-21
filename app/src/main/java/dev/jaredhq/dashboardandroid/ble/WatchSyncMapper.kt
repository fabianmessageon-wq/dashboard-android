package dev.jaredhq.dashboardandroid.ble

import dev.jaredhq.dashboardandroid.domain.model.WatchSyncEvent
import dev.jaredhq.dashboardandroid.domain.model.WatchSyncRequest
import java.time.Instant

/**
 * Pure mapping from the live BLE state to the Phase 2 [WatchSyncRequest] telemetry
 * payload. Kept free of Android/BLE side effects so it is fully unit-testable.
 */
object WatchSyncMapper {

    /**
     * @return a [WatchSyncRequest] describing the current connection, or null when
     *   no device can be identified (nothing meaningful to report — e.g. idle or a
     *   bare scan).
     */
    fun build(
        state: WatchConnectionState,
        connectedAtMillis: Long?,
        disconnectedAtMillis: Long?,
        lastDeviceAddress: String?,
        lastDeviceName: String?,
        lastMacAddress: String?,
        rawEvents: List<WatchPacketLogger.RawEvent>,
    ): WatchSyncRequest? {
        val connected = state as? WatchConnectionState.Connected

        // The MAC from command 301 (CMD_GET_MAC_ADDRESS) is the stable identity; fall back to the
        // BLE address (current or last-known) when the MAC hasn't been read yet.
        val mac = connected?.macAddress ?: lastMacAddress
        val address = connected?.deviceAddress
            ?: (state as? WatchConnectionState.Connecting)?.deviceAddress
            ?: lastDeviceAddress
        val deviceId = (mac ?: address)?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: return null

        return WatchSyncRequest(
            deviceId = deviceId,
            deviceName = connected?.deviceName ?: lastDeviceName,
            connectedAt = connectedAtMillis?.let { iso(it) },
            // A disconnect time is only relevant once the connection has ended.
            disconnectedAt = if (connected == null) disconnectedAtMillis?.let { iso(it) } else null,
            batteryPercent = connected?.batteryPercent,
            mtu = connected?.mtu,
            connectionState = state.wireName(),
            protocolVersion = null,
            rawEvents = rawEvents.map { it.toSyncEvent() },
        )
    }

    private fun WatchConnectionState.wireName(): String = when (this) {
        is WatchConnectionState.Connected -> "connected"
        is WatchConnectionState.Connecting -> "connecting"
        is WatchConnectionState.Scanning -> "scanning"
        is WatchConnectionState.Disconnected -> "disconnected"
        is WatchConnectionState.Error -> "error"
    }

    private fun WatchPacketLogger.RawEvent.toSyncEvent(): WatchSyncEvent = WatchSyncEvent(
        direction = direction,
        characteristic = shortCharId(characteristicUuid),
        commandFamily = hex.take(2).takeIf { it.length == 2 }?.let { "0x${it.uppercase()}" },
        hex = hex,
        timestamp = iso(timestamp),
    )

    /** "00000af6-0000-…" → "0x0AF6" (the 16-bit assigned-number form), else the raw UUID. */
    private fun shortCharId(uuid: String): String =
        if (uuid.length >= 8) "0x${uuid.substring(4, 8).uppercase()}" else uuid

    private fun iso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
}
