package dev.jaredhq.dashboardandroid.ble

/**
 * Reassembles a fragmented `33 DA AD` activity buffer from successive `0x0AF7`
 * notification chunks.
 *
 * ## Scope (Private Phase 2 — reassembly only)
 *
 * This class does the bare minimum: detect activity chunks, stitch them back into the
 * full activity buffer, and report progress/completion. It does **not** decode any
 * activity fields and does **not** generate TCX — those are later phases. Callers feed
 * a completed buffer to [WatchProtocol.activityVersion] / [WatchProtocol.describeActivityVersion]
 * and keep unsupported versions non-fatal.
 *
 * ## Wire semantics (reference-documented, capture-gated)
 *
 * Mirrors the IDO/VeryFit/Ryze Web-Bluetooth reference downloader:
 *
 * - Every activity notification starts with header byte `0x33`.
 * - A chunk whose bytes `1..4` are `DA AD DA AD` is a **preamble** that opens a new
 *   buffer. Its bytes `6..7` carry the total reassembled length as a little-endian
 *   `uint16` — that length counts the buffer **from the `DA AD` preamble onward**
 *   (i.e. everything except the leading `0x33` of each chunk).
 * - For every `0x33` chunk (preamble included), the bytes **from offset 1 onward** are
 *   appended to the buffer.
 * - The buffer is complete once the appended byte count reaches the declared length;
 *   the reference reads the activity version at full-buffer offset
 *   [WatchProtocol.ACTIVITY_DATA_OFFSET] (25).
 *
 * The exact length arithmetic and offsets remain capture-gated until confirmed against
 * a real multi-chunk capture from Fabian's watch, so completion is reported alongside
 * the raw byte counts for log-level confirmation, and a malformed/oversized length is
 * dropped non-fatally rather than allocating an absurd buffer.
 *
 * Not thread-safe: a single connection's notifications arrive serially on the GATT
 * binder thread, and a fresh instance is used per connection.
 */
class WatchActivityReassembler(
    /** Reject a declared length larger than this so a corrupt preamble can't allocate wildly. */
    private val maxBufferBytes: Int = 64 * 1024,
) {

    private var buffer: ByteArray? = null
    private var written: Int = 0

    /** Outcome of feeding one notification chunk to [accept]. */
    sealed interface Result {
        /** Not an activity chunk (not `0x33`), or a `0x33` continuation with no buffer in flight. */
        data object Ignored : Result

        /** A preamble opened a new buffer; still waiting for more bytes. */
        data class Started(val received: Int, val expected: Int) : Result

        /** A continuation chunk extended the in-flight buffer; still incomplete. */
        data class Appended(val received: Int, val expected: Int) : Result

        /** The buffer is fully reassembled and ready for version inspection. */
        data class Complete(val activityBuffer: ByteArray) : Result

        /** A preamble declared an implausible length (<= 0 or > [maxBufferBytes]); dropped. */
        data class Malformed(val declaredLength: Int) : Result
    }

    /** True while a buffer is partially assembled (a preamble has been seen, not yet complete). */
    fun isInFlight(): Boolean = buffer != null

    /**
     * Feed one received notification. Never throws. Returns [Result.Ignored] for any
     * chunk that is not part of an activity buffer so the caller can fall through to its
     * generic frame logging.
     */
    fun accept(chunk: ByteArray): Result {
        if (chunk.isEmpty() || chunk[0] != ACTIVITY_HEADER) return Result.Ignored

        if (isPreamble(chunk)) {
            // A new preamble always restarts assembly — any partial buffer is dropped
            // (the reference allocates a fresh rx_buffer on each preamble).
            val length = (chunk[6].toInt() and 0xFF) or ((chunk[7].toInt() and 0xFF) shl 8)
            if (length <= 0 || length > maxBufferBytes) {
                reset()
                return Result.Malformed(length)
            }
            val buf = ByteArray(length)
            buffer = buf
            written = 0
            return if (copyChunk(chunk)) completeAndReset() else Result.Started(written, length)
        }

        // Continuation chunk: only meaningful while a preamble buffer is in flight.
        val buf = buffer ?: return Result.Ignored
        return if (copyChunk(chunk)) completeAndReset() else Result.Appended(written, buf.size)
    }

    /** Discard any in-flight buffer (e.g. on disconnect). */
    fun reset() {
        buffer = null
        written = 0
    }

    /**
     * Append `chunk[1..]` into the fixed-size buffer, clamped so a chunk longer than the
     * remaining space can't overrun the declared length. Returns true when the buffer is full.
     */
    private fun copyChunk(chunk: ByteArray): Boolean {
        val buf = buffer ?: return false
        val remaining = buf.size - written
        val toCopy = minOf(chunk.size - 1, remaining)
        if (toCopy > 0) {
            System.arraycopy(chunk, 1, buf, written, toCopy)
            written += toCopy
        }
        return written >= buf.size
    }

    private fun completeAndReset(): Result.Complete {
        val complete = buffer ?: ByteArray(0)
        reset()
        return Result.Complete(complete)
    }

    private fun isPreamble(chunk: ByteArray): Boolean =
        chunk.size >= 8 &&
            chunk[1] == 0xDA.toByte() &&
            chunk[2] == 0xAD.toByte() &&
            chunk[3] == 0xDA.toByte() &&
            chunk[4] == 0xAD.toByte()

    private companion object {
        const val ACTIVITY_HEADER: Byte = 0x33
    }
}
