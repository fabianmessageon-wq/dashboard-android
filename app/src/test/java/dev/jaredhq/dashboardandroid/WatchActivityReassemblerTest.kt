package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.ble.WatchActivityReassembler
import dev.jaredhq.dashboardandroid.ble.WatchProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin checks for [WatchActivityReassembler] (Private Phase 2).
 *
 * These verify the reference-documented `33 DA AD` reassembly behaviour: every `0x33`
 * chunk contributes its bytes from offset 1 onward; the preamble's little-endian uint16
 * at bytes 6..7 is the total reassembled length (counted from the `DA AD` preamble); the
 * buffer completes on the exact byte count; the activity version sits at full-buffer
 * offset 25. The reassembler does NOT decode fields — only the version helper is exercised.
 *
 * The framing itself is still capture-gated against Fabian's watch; these tests pin the
 * *algorithm* against the reference downloader's logic and the one captured preamble sample.
 */
class WatchActivityReassemblerTest {

    private val header = 0x33.toByte()

    @Test
    fun preambleOnlyChunk_startsButStaysIncomplete() {
        // Captured-style preamble: declaredLen (bytes 6..7) = 0x1D 0x00 = 29, but only
        // 15 bytes (offset 1..15) are present — more chunks must follow.
        val preamble = byteArrayOf(
            0x33, 0xDA.toByte(), 0xAD.toByte(), 0xDA.toByte(), 0xAD.toByte(), 0x01, 0x1D, 0x00,
            0x01, 0x40, 0x33, 0x00, 0x10, 0x00, 0x00, 0x00,
        )
        val reassembler = WatchActivityReassembler()
        val result = reassembler.accept(preamble)
        assertTrue(result is WatchActivityReassembler.Result.Started)
        result as WatchActivityReassembler.Result.Started
        assertEquals(29, result.expected)
        assertEquals(15, result.received) // preamble.size - 1
        assertTrue(reassembler.isInFlight())
    }

    @Test
    fun twoChunkSequence_reassemblesAndReadsSupportedVersion() {
        // Total reassembled length = 30. Preamble carries 15 bytes (offset 1..15),
        // continuation carries the remaining 15, with activity version 16 landing at
        // full-buffer offset 25 (= continuation chunk index 11).
        val preamble = ByteArray(16).also {
            it[0] = header
            it[1] = 0xDA.toByte(); it[2] = 0xAD.toByte(); it[3] = 0xDA.toByte(); it[4] = 0xAD.toByte()
            it[5] = 0x01 // frame version byte (not the activity version)
            it[6] = 0x1E; it[7] = 0x00 // declared length 30 (LE)
        }
        val continuation = ByteArray(16).also {
            it[0] = header
            it[11] = 16 // buffer offset 15 + (11 - 1) = 25 → activity version
        }

        val reassembler = WatchActivityReassembler()
        val started = reassembler.accept(preamble)
        assertTrue(started is WatchActivityReassembler.Result.Started)
        assertEquals(15, (started as WatchActivityReassembler.Result.Started).received)

        val done = reassembler.accept(continuation)
        assertTrue(done is WatchActivityReassembler.Result.Complete)
        val buffer = (done as WatchActivityReassembler.Result.Complete).activityBuffer
        assertEquals(30, buffer.size)
        assertEquals(16, WatchProtocol.activityVersion(buffer))
        assertTrue(WatchProtocol.isSupportedActivityVersion(WatchProtocol.activityVersion(buffer)!!))
        // Reassembler resets after completion so the next buffer starts clean.
        assertFalse(reassembler.isInFlight())
    }

    @Test
    fun completeBuffer_withUnsupportedVersion_isNonFatal() {
        val preamble = ByteArray(16).also {
            it[0] = header
            it[1] = 0xDA.toByte(); it[2] = 0xAD.toByte(); it[3] = 0xDA.toByte(); it[4] = 0xAD.toByte()
            it[6] = 0x1E; it[7] = 0x00 // length 30
        }
        val continuation = ByteArray(16).also {
            it[0] = header
            it[11] = 1 // activity version 1 at buffer offset 25 — unsupported
        }
        val reassembler = WatchActivityReassembler()
        reassembler.accept(preamble)
        val done = reassembler.accept(continuation) as WatchActivityReassembler.Result.Complete
        val version = WatchProtocol.activityVersion(done.activityBuffer)!!
        assertFalse(WatchProtocol.isSupportedActivityVersion(version))
        val desc = WatchProtocol.describeActivityVersion(version)
        assertTrue(desc.contains("Unsupported activity-data version"))
        assertTrue(desc.contains("BLE connection unaffected"))
    }

    @Test
    fun preambleChunkLongerThanDeclaredLength_completesImmediately() {
        // declaredLen = 8, so the buffer fills from the preamble chunk alone (15 payload
        // bytes available, clamped to 8). Overrun must not throw.
        val preamble = byteArrayOf(
            0x33, 0xDA.toByte(), 0xAD.toByte(), 0xDA.toByte(), 0xAD.toByte(), 0x01, 0x08, 0x00,
            0x01, 0x40, 0x33, 0x00, 0x10, 0x00, 0x00, 0x00,
        )
        val done = WatchActivityReassembler().accept(preamble)
        assertTrue(done is WatchActivityReassembler.Result.Complete)
        assertEquals(8, (done as WatchActivityReassembler.Result.Complete).activityBuffer.size)
    }

    @Test
    fun nonActivityChunk_isIgnored() {
        // A 02:01 basic-info response is not a 0x33 activity chunk.
        val basicInfo = byteArrayOf(0x02, 0x01, 0xD8.toByte(), 0x1E, 0x01, 0x00, 0x00, 0x5F)
        assertEquals(WatchActivityReassembler.Result.Ignored, WatchActivityReassembler().accept(basicInfo))
    }

    @Test
    fun continuationWithoutPreamble_isIgnored() {
        // A 0x33 chunk that is NOT a preamble and arrives with no buffer in flight has
        // nothing to extend — ignored so the caller falls through to generic logging.
        val orphan = byteArrayOf(0x33, 0x01, 0x02, 0x03, 0x04, 0x05)
        val reassembler = WatchActivityReassembler()
        assertEquals(WatchActivityReassembler.Result.Ignored, reassembler.accept(orphan))
        assertFalse(reassembler.isInFlight())
    }

    @Test
    fun newPreambleMidBuffer_dropsPartialAndRestarts() {
        val firstPreamble = ByteArray(16).also {
            it[0] = header
            it[1] = 0xDA.toByte(); it[2] = 0xAD.toByte(); it[3] = 0xDA.toByte(); it[4] = 0xAD.toByte()
            it[6] = 0x64; it[7] = 0x00 // length 100 — will stay incomplete
        }
        val secondPreamble = ByteArray(16).also {
            it[0] = header
            it[1] = 0xDA.toByte(); it[2] = 0xAD.toByte(); it[3] = 0xDA.toByte(); it[4] = 0xAD.toByte()
            it[6] = 0x1E; it[7] = 0x00 // length 30
        }
        val reassembler = WatchActivityReassembler()
        assertTrue(reassembler.accept(firstPreamble) is WatchActivityReassembler.Result.Started)
        // A fresh preamble restarts assembly with the new declared length, not 100 + 15.
        val restarted = reassembler.accept(secondPreamble)
        assertTrue(restarted is WatchActivityReassembler.Result.Started)
        assertEquals(30, (restarted as WatchActivityReassembler.Result.Started).expected)
        assertEquals(15, restarted.received)
    }

    @Test
    fun malformedZeroLength_isDroppedNonFatally() {
        val preamble = byteArrayOf(
            0x33, 0xDA.toByte(), 0xAD.toByte(), 0xDA.toByte(), 0xAD.toByte(), 0x01, 0x00, 0x00,
        )
        val reassembler = WatchActivityReassembler()
        val result = reassembler.accept(preamble)
        assertTrue(result is WatchActivityReassembler.Result.Malformed)
        assertEquals(0, (result as WatchActivityReassembler.Result.Malformed).declaredLength)
        assertFalse(reassembler.isInFlight())
    }

    @Test
    fun oversizedLength_isDroppedNonFatally() {
        val reassembler = WatchActivityReassembler(maxBufferBytes = 32)
        val preamble = byteArrayOf(
            0x33, 0xDA.toByte(), 0xAD.toByte(), 0xDA.toByte(), 0xAD.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte(),
        )
        assertTrue(reassembler.accept(preamble) is WatchActivityReassembler.Result.Malformed)
    }

    @Test
    fun emptyChunk_isIgnored() {
        assertEquals(WatchActivityReassembler.Result.Ignored, WatchActivityReassembler().accept(ByteArray(0)))
    }
}
