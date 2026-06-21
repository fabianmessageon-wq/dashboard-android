package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.ble.WatchConnectionState
import dev.jaredhq.dashboardandroid.ble.WatchPacketLogger
import dev.jaredhq.dashboardandroid.ble.WatchSyncMapper
import dev.jaredhq.dashboardandroid.data.api.dto.toDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 telemetry mapping: [WatchSyncMapper] turns the live BLE state into a
 * [dev.jaredhq.dashboardandroid.domain.model.WatchSyncRequest], and the DTO
 * mapper renders it into the documented wire shape.
 */
class WatchSyncMappingTest {

    private val noEvents = emptyList<WatchPacketLogger.RawEvent>()

    @Test
    fun connectedPrefersMacAddressAndReportsTelemetry() {
        val request = WatchSyncMapper.build(
            state = WatchConnectionState.Connected(
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                deviceName = "Kogan Active 4 Pro",
                batteryPercent = 78,
                mtu = 247,
                macAddress = "f4:91:29:51:c6:45",
            ),
            connectedAtMillis = 0L,
            disconnectedAtMillis = null,
            lastDeviceAddress = "AA:BB:CC:DD:EE:FF",
            lastDeviceName = "Kogan Active 4 Pro",
            lastMacAddress = "f4:91:29:51:c6:45",
            rawEvents = noEvents,
        )

        requireNotNull(request)
        // The MAC from the handshake wins over the BLE address as the stable id.
        assertEquals("f4:91:29:51:c6:45", request.deviceId)
        assertEquals("Kogan Active 4 Pro", request.deviceName)
        assertEquals("connected", request.connectionState)
        assertEquals(78, request.batteryPercent)
        assertEquals(247, request.mtu)
        assertEquals("1970-01-01T00:00:00Z", request.connectedAt)
        // Still connected ⇒ no disconnect time.
        assertNull(request.disconnectedAt)
    }

    @Test
    fun disconnectReportsLastIdentityAndDisconnectTime() {
        val request = WatchSyncMapper.build(
            state = WatchConnectionState.Disconnected,
            connectedAtMillis = 1_000L,
            disconnectedAtMillis = 5_000L,
            lastDeviceAddress = "AA:BB:CC:DD:EE:FF",
            lastDeviceName = "Kogan Active 4 Pro",
            lastMacAddress = "f4:91:29:51:c6:45",
            rawEvents = noEvents,
        )

        requireNotNull(request)
        assertEquals("f4:91:29:51:c6:45", request.deviceId)
        assertEquals("disconnected", request.connectionState)
        assertEquals("1970-01-01T00:00:01Z", request.connectedAt)
        assertEquals("1970-01-01T00:00:05Z", request.disconnectedAt)
        // No live connection ⇒ no battery/MTU snapshot.
        assertNull(request.batteryPercent)
        assertNull(request.mtu)
    }

    @Test
    fun connectingUsesStateAddressAsId() {
        val request = WatchSyncMapper.build(
            state = WatchConnectionState.Connecting("AA:BB:CC:DD:EE:FF"),
            connectedAtMillis = null,
            disconnectedAtMillis = null,
            lastDeviceAddress = null,
            lastDeviceName = null,
            lastMacAddress = null,
            rawEvents = noEvents,
        )

        requireNotNull(request)
        assertEquals("AA:BB:CC:DD:EE:FF", request.deviceId)
        assertEquals("connecting", request.connectionState)
    }

    @Test
    fun noIdentifiableDeviceYieldsNull() {
        val request = WatchSyncMapper.build(
            state = WatchConnectionState.Disconnected,
            connectedAtMillis = null,
            disconnectedAtMillis = null,
            lastDeviceAddress = null,
            lastDeviceName = null,
            lastMacAddress = null,
            rawEvents = noEvents,
        )
        assertNull(request)
    }

    @Test
    fun rawEventsMapToWireShape() {
        val request = WatchSyncMapper.build(
            state = WatchConnectionState.Connected(
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                macAddress = "f4:91:29:51:c6:45",
            ),
            connectedAtMillis = 0L,
            disconnectedAtMillis = null,
            lastDeviceAddress = "AA:BB:CC:DD:EE:FF",
            lastDeviceName = null,
            lastMacAddress = "f4:91:29:51:c6:45",
            rawEvents = listOf(
                WatchPacketLogger.RawEvent(
                    timestamp = 0L,
                    direction = WatchPacketLogger.DIRECTION_TX,
                    characteristicUuid = "00000af6-0000-1000-8000-00805f9b34fb",
                    hex = "0204",
                ),
            ),
        )

        requireNotNull(request)
        val event = request.rawEvents.single()
        assertEquals("phone->watch", event.direction)
        assertEquals("0x0AF6", event.characteristic)
        assertEquals("0x02", event.commandFamily)
        assertEquals("0204", event.hex)
        assertEquals("1970-01-01T00:00:00Z", event.timestamp)
    }

    @Test
    fun toDtoCarriesEveryField() {
        val request = WatchSyncMapper.build(
            state = WatchConnectionState.Connected(
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                deviceName = "Watch",
                batteryPercent = 50,
                mtu = 247,
                macAddress = "f4:91:29:51:c6:45",
            ),
            connectedAtMillis = 0L,
            disconnectedAtMillis = null,
            lastDeviceAddress = "AA:BB:CC:DD:EE:FF",
            lastDeviceName = "Watch",
            lastMacAddress = "f4:91:29:51:c6:45",
            rawEvents = noEvents,
        )!!

        val dto = request.toDto()
        assertEquals(request.deviceId, dto.deviceId)
        assertEquals(request.deviceName, dto.deviceName)
        assertEquals(request.connectionState, dto.connectionState)
        assertEquals(request.batteryPercent, dto.batteryPercent)
        assertEquals(request.mtu, dto.mtu)
        assertEquals(request.connectedAt, dto.connectedAt)
        assertTrue(dto.rawEvents.isEmpty())
    }
}
