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
    fun parseFrame_transportAckIsNotCrcValid_soBatteryHeuristicMustNotFire() {
        // Real on-device transport/ACK frame observed 2026-06-22 on the watch:
        // `07 40 1F 00 …`. Its first byte (0x07) would be misread as "7% battery" by the
        // raw heuristic. The frame's CRC must NOT validate, which is how the GATT callback
        // gates out this false reading (it only trusts the heuristic on crcValid frames).
        val ack = byteArrayOf(0x07, 0x40, 0x1F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val frame = WatchProtocol.parseFrame(ack)
        assertNotNull(frame)
        frame!!
        assertTrue("transport ACK must not pass as a valid IDO frame", !frame.crcValid)
        // The raw heuristic alone WOULD misread it — proving why the crcValid gate matters.
        assertEquals(7, WatchProtocol.parseBatteryInfoFromBinary(ack)?.level)
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

    @Test
    fun parseHexCommand_acceptsCopiedCaptureFormats() {
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 0x01, 0x41, 0x01, 0x00, 0x00, 0x00, 0xC3.toByte(), 0xF7.toByte()),
            WatchProtocol.parseHexCommand("AB 01:41-01,00 00 00 C3 F7")!!,
        )
    }

    @Test
    fun parseHexCommand_rejectsInvalidInput() {
        assertNull(WatchProtocol.parseHexCommand("AB 01 4"))
        assertNull(WatchProtocol.parseHexCommand("not hex"))
    }

    @Test
    fun capturedStatusProbe_matchesOfficialCaptureCommand() {
        assertArrayEquals(byteArrayOf(0x02, 0x01), WatchProtocol.buildCapturedStatusProbeCommand())
    }

    @Test
    fun parseBatteryInfoFromCapturedStatus_readsObservedBatteryByte() {
        val response = byteArrayOf(
            0x02, 0x01, 0xD8.toByte(), 0x1E, 0x01, 0x01, 0x00, 0x5F,
            0x01, 0x00, 0x01, 0x00, 0x5A, 0x02, 0x02, 0x03, 0x06, 0x00,
        )
        val info = WatchProtocol.parseBatteryInfoFromCapturedStatus(response)
        assertNotNull(info)
        info!!
        assertEquals(95, info.level)
        assertEquals(0, info.voltage)
    }

    @Test
    fun parseBatteryInfoFromCapturedBatteryPoll_readsObservedBatteryByte() {
        // Captured from btsnoop: WRITE 02 A7 → NTF 02 A7 01 01 00 5F 01 D8 1E (watch-verified)
        val response = byteArrayOf(0x02, 0xA7.toByte(), 0x01, 0x01, 0x00, 0x5F, 0x01, 0xD8.toByte(), 0x1E)
        val info = WatchProtocol.parseBatteryInfoFromCapturedBatteryPoll(response)
        assertNotNull(info)
        info!!
        assertEquals(95, info.level)
    }

    @Test
    fun parseBatteryInfoFromCapturedBatteryPoll_rejectsWrongCommand() {
        val response = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x5F)
        assertNull(WatchProtocol.parseBatteryInfoFromCapturedBatteryPoll(response))
    }

    @Test
    fun parseBatteryInfoFromCapturedBatteryPoll_rejectsTooShort() {
        assertNull(WatchProtocol.parseBatteryInfoFromCapturedBatteryPoll(byteArrayOf(0x02, 0xA7.toByte(), 0x01)))
    }

    @Test
    fun parseMacAddressFromCapturedMacResponse_extractsWatchMac() {
        // Captured from btsnoop: NTF 02 04 F4 91 29 51 C6 45 F4 91 29 51 C6 45 (watch-verified)
        val response = byteArrayOf(
            0x02, 0x04,
            0xF4.toByte(), 0x91.toByte(), 0x29, 0x51, 0xC6.toByte(), 0x45,
            0xF4.toByte(), 0x91.toByte(), 0x29, 0x51, 0xC6.toByte(), 0x45,
        )
        val mac = WatchProtocol.parseMacAddressFromCapturedMacResponse(response)
        assertEquals("F4:91:29:51:C6:45", mac)
    }

    @Test
    fun parseMacAddressFromCapturedMacResponse_rejectsWrongCommand() {
        val response = byteArrayOf(0x02, 0x01, 0xF4.toByte(), 0x91.toByte(), 0x29, 0x51, 0xC6.toByte(), 0x45)
        assertNull(WatchProtocol.parseMacAddressFromCapturedMacResponse(response))
    }

    @Test
    fun parseMacAddressFromCapturedMacResponse_rejectsAllZeroAndAllFf() {
        val zeros = byteArrayOf(0x02, 0x04, 0, 0, 0, 0, 0, 0)
        assertNull(WatchProtocol.parseMacAddressFromCapturedMacResponse(zeros))
        val ffs = byteArrayOf(
            0x02, 0x04,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        assertNull(WatchProtocol.parseMacAddressFromCapturedMacResponse(ffs))
    }

    @Test
    fun parseMacAddressFromCapturedMacResponse_rejectsMismatchedDuplicate() {
        // 14-byte response whose two echoed MAC copies disagree → corrupt, must be dropped.
        val response = byteArrayOf(
            0x02, 0x04,
            0xF4.toByte(), 0x91.toByte(), 0x29, 0x51, 0xC6.toByte(), 0x45,
            0xF4.toByte(), 0x91.toByte(), 0x29, 0x51, 0xC6.toByte(), 0x46, // last byte differs
        )
        assertNull(WatchProtocol.parseMacAddressFromCapturedMacResponse(response))
    }

    @Test
    fun parseMacAddressFromCapturedMacResponse_acceptsSingleCopyEightBytes() {
        // Only one MAC copy present (no duplicate to cross-check) — still valid.
        val response = byteArrayOf(0x02, 0x04, 0xF4.toByte(), 0x91.toByte(), 0x29, 0x51, 0xC6.toByte(), 0x45)
        assertEquals("F4:91:29:51:C6:45", WatchProtocol.parseMacAddressFromCapturedMacResponse(response))
    }

    @Test
    fun buildMacAddressCommand_isCaptureVerified() {
        assertArrayEquals(byteArrayOf(0x02, 0x04), WatchProtocol.buildMacAddressCommand())
    }

    @Test
    fun buildBatteryInfoCommand_isCaptureVerified() {
        assertArrayEquals(byteArrayOf(0x02, 0xA7.toByte()), WatchProtocol.buildBatteryInfoCommand())
    }

    @Test
    fun capturedDaAdFrame_isRecognisedWithoutMisparsingAsOldFrame() {
        val response = byteArrayOf(
            0x33, 0xDA.toByte(), 0xAD.toByte(), 0xDA.toByte(), 0xAD.toByte(), 0x01, 0x1D, 0x00,
            0x01, 0x40, 0x33, 0x00, 0x10, 0x00, 0x00, 0x00,
        )
        assertTrue(WatchProtocol.looksLikeCapturedDaAdFrame(response))
        assertTrue(WatchProtocol.describeCapturedDaAdFrame(response).contains("declaredLen=29"))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
