package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.ble.WatchProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin checks for the IDO V3 protocol helpers in [WatchProtocol].
 *
 * These verify the *algorithmic* core (CRC16 variants, frame assembly/parse
 * roundtrip, battery extraction). They do NOT assert that the unverified framing
 * constants (head byte, battery cmd/key) match the real watch — that still requires
 * an on-device BLE capture. The CRC known-answer tests use the canonical "123456789"
 * check values so a future change to the CRC code is caught immediately.
 */
class WatchProtocolTest {

    @Test
    fun crc16Ccitt_matchesCanonicalCheckValue() {
        val msg = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, WatchProtocol.crc16Ccitt(msg, 0, msg.size))
    }

    @Test
    fun crc16Arc_matchesCanonicalCheckValue() {
        val msg = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0xBB3D, WatchProtocol.crc16Arc(msg, 0, msg.size))
    }

    @Test
    fun buildFrame_thenParse_roundtripsWithValidCrc() {
        val payload = byteArrayOf(78, 1, 0x68, 0x10) // level=78, status=1, voltage=4200mV (LE)
        val frame = WatchProtocol.buildFrame(cmd = 0x01, key = 0x41, payload = payload)
        val parsed = WatchProtocol.parseFrame(frame)
        assertNotNull(parsed)
        parsed!!
        assertEquals(0x01, parsed.cmd)
        assertEquals(0x41, parsed.key)
        assertEquals(payload.size, parsed.declaredLen)
        assertArrayEquals(payload, parsed.payload)
        assertTrue("CCITT CRC must validate on our own frames", parsed.crcValid)
    }

    @Test
    fun parseBatteryInfoFromBinary_readsLevelStatusVoltage() {
        val payload = byteArrayOf(78, 1, 0x68, 0x10)
        val info = WatchProtocol.parseBatteryInfoFromBinary(payload)
        assertNotNull(info)
        info!!
        assertEquals(78, info.level)
        assertEquals(1, info.status)
        assertEquals(4200, info.voltage)
    }

    @Test
    fun parseBatteryInfoFromBinary_rejectsImplausibleLevel() {
        // 0xFF (255) is not a valid percentage — must not be misread as battery.
        assertNull(WatchProtocol.parseBatteryInfoFromBinary(byteArrayOf(0xFF.toByte(), 0)))
    }

    @Test
    fun parseBatteryInfoFromJson_handlesNativeKeyShape() {
        val json = """{"level":78,"status":1,"voltage":4200,"mode":0,
            |"lastChargingYear":2026,"lastChargingMonth":6,"lastChargingDay":21,
            |"lastChargingHour":9,"lastChargingMinute":30,"lastChargingSecond":0}""".trimMargin()
        val info = WatchProtocol.parseBatteryInfoFromJson(json.toByteArray())
        assertNotNull(info)
        info!!
        assertEquals(78, info.level)
        assertEquals(4200, info.voltage)
        assertEquals("2026-06-21T09:30:00", info.lastChargingTime)
    }

    @Test
    fun looksLikeJson_discriminatesBinaryFromAscii() {
        assertTrue(WatchProtocol.looksLikeJson("{\"a\":1}".toByteArray()))
        assertTrue(!WatchProtocol.looksLikeJson(byteArrayOf(0xAB.toByte(), 0x01, 0x41)))
    }

    @Test
    fun describeShortStatus_labelsSixByteAckWithoutPretendingBattery() {
        val desc = WatchProtocol.describeShortStatus(
            byteArrayOf(0xAD.toByte(), 0x01, 0x32, 0x00, 0x00, 0x00),
        )
        assertTrue(desc.contains("Short status/ACK"))
        assertTrue(desc.contains("head=0xAD"))
        assertTrue(desc.contains("key=0x32"))
        assertTrue(desc.contains("no payload"))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
