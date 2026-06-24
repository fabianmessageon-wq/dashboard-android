package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.ble.ActivityFieldConfidence
import dev.jaredhq.dashboardandroid.ble.WatchActivitySummary
import dev.jaredhq.dashboardandroid.ble.WatchProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-Kotlin checks for the Private Phase 3 activity-summary decode in
 * [WatchProtocol.parseActivitySummary].
 *
 * The byte offsets under test are REFERENCE_ONLY (no real version-16 capture from Fabian's
 * watch exists yet — on-device we have only seen an empty version-0 buffer). So these tests
 * pin the *decode algorithm and its safety behaviour* against a synthetic buffer built to the
 * reference layout; they do NOT prove the layout matches the real watch. A real capture will
 * either confirm the offsets or send us back to the single offset table in [WatchProtocol].
 */
class WatchActivitySummaryTest {

    /** Activity data section starts at offset 25 of the reassembled buffer. */
    private val base = WatchProtocol.ACTIVITY_DATA_OFFSET

    /** Build a reassembled buffer of [size] whose data section ([base]..) is populated by [fill]. */
    private fun activityBuffer(size: Int, fill: ByteArray.() -> Unit): ByteArray =
        ByteArray(size).apply(fill)

    private fun ByteArray.putU32LE(absOffset: Int, value: Long) {
        this[absOffset] = (value and 0xFF).toByte()
        this[absOffset + 1] = ((value shr 8) and 0xFF).toByte()
        this[absOffset + 2] = ((value shr 16) and 0xFF).toByte()
        this[absOffset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    @Test
    fun decodesSyntheticV16Buffer_allSummaryFields() {
        val buf = activityBuffer(50) {
            this[base + 0] = 16            // version
            this[base + 1] = 7             // activity type
            putU32LE(base + 2, 287454020L) // start time (0x11223344)
            putU32LE(base + 6, 3600L)      // duration seconds
            putU32LE(base + 10, 5000L)     // steps
            putU32LE(base + 14, 4200L)     // distance metres
            putU32LE(base + 18, 350L)      // calories
            this[base + 22] = 120.toByte() // avg hr
            this[base + 23] = 150.toByte() // max hr
            this[base + 24] = 60.toByte()  // min hr
        }

        val s = WatchProtocol.parseActivitySummary(buf)!!
        assertEquals(16, s.activityVersion)
        assertEquals(7, s.activityType)
        assertEquals(287454020L, s.startTimeEpochSeconds)
        assertEquals(3600L, s.durationSeconds)
        assertEquals(5000L, s.steps)
        assertEquals(4200L, s.distanceMeters)
        assertEquals(350L, s.calories)
        assertEquals(120, s.avgHeartRate)
        assertEquals(150, s.maxHeartRate)
        assertEquals(60, s.minHeartRate)
    }

    @Test
    fun perFieldConfidence_versionCaptureObserved_restReferenceOnly() {
        val buf = activityBuffer(50) { this[base] = 16 }
        val s = WatchProtocol.parseActivitySummary(buf)!!
        // The version byte's *position* has been seen on-device; every other field is a
        // reference-only hypothesis until a real v16 capture confirms it.
        assertEquals(ActivityFieldConfidence.CAPTURE_OBSERVED, s.confidenceFor(WatchActivitySummary.Field.VERSION))
        for (field in WatchActivitySummary.Field.entries) {
            if (field != WatchActivitySummary.Field.VERSION) {
                assertEquals(
                    "expected REFERENCE_ONLY for $field",
                    ActivityFieldConfidence.REFERENCE_ONLY,
                    s.confidenceFor(field),
                )
            }
        }
    }

    @Test
    fun zeroHeartRate_isTreatedAsAbsent() {
        val buf = activityBuffer(50) {
            this[base] = 16
            this[base + 22] = 0 // avg
            this[base + 23] = 0 // max
            this[base + 24] = 0 // min
        }
        val s = WatchProtocol.parseActivitySummary(buf)!!
        assertNull(s.avgHeartRate)
        assertNull(s.maxHeartRate)
        assertNull(s.minHeartRate)
    }

    @Test
    fun unsupportedVersion_returnsNull() {
        val buf = activityBuffer(50) { this[base] = 1 } // firmware-ish version, not activity v16
        assertNull(WatchProtocol.parseActivitySummary(buf))
    }

    @Test
    fun bufferTooShortForVersion_returnsNull() {
        // Length 25 means index 25 (the version byte) does not exist.
        assertNull(WatchProtocol.parseActivitySummary(ByteArray(base)))
    }

    @Test
    fun truncatedV16Buffer_decodesPrefixAndNullsMissingTail() {
        // Holds version + type + start time (through abs offset 30) but is cut off before
        // duration/steps/.../HR — those must come back null, and decode must not throw.
        val buf = activityBuffer(base + 6) {
            this[base] = 16
            this[base + 1] = 3
            putU32LE(base + 2, 1000L)
        }
        val s = WatchProtocol.parseActivitySummary(buf)!!
        assertEquals(16, s.activityVersion)
        assertEquals(3, s.activityType)
        assertEquals(1000L, s.startTimeEpochSeconds)
        assertNull(s.durationSeconds)
        assertNull(s.steps)
        assertNull(s.distanceMeters)
        assertNull(s.calories)
        assertNull(s.avgHeartRate)
    }

    @Test
    fun emptyVersionZeroBuffer_returnsNull() {
        // Mirrors the only buffer seen on-device so far: 125 bytes, activity version 0.
        val buf = ByteArray(125) // version byte at offset 25 defaults to 0
        assertNull(WatchProtocol.parseActivitySummary(buf))
    }

    @Test
    fun buildActivitySyncRequestV16Variant_matchesReferenceVariantBytes() {
        assertArrayEquals(
            byteArrayOf(
                0x33, 0xDA.toByte(), 0xAD.toByte(), 0xDA.toByte(), 0xAD.toByte(),
                0x01, 0x10, 0x00, 0x04, 0x00, 0x16, 0x00, 0x00, 0x04,
                0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            WatchProtocol.buildActivitySyncRequestV16Variant(),
        )
    }
}
