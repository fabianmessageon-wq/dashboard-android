package dev.jaredhq.dashboardandroid.ble

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory ring buffer for raw BLE packet logging.
 *
 * Developer-only diagnostic tool. Never persisted beyond the session.
 * Thread-safe for concurrent access from GATT callbacks and UI reads.
 */
class WatchPacketLogger(private val maxEntries: Int = 200) {

    data class LogEntry(
        val timestamp: Long,
        val tag: String,
        val message: String,
    )

    /**
     * A structured record of a single raw BLE packet, captured alongside the
     * human-readable [LogEntry] feed so Phase 2 can upload it to the dashboard.
     *
     * @property direction [DIRECTION_TX] (phone→watch) or [DIRECTION_RX] (watch→phone).
     * @property characteristicUuid Full characteristic UUID the packet went to/from.
     * @property hex Contiguous lowercase payload hex (e.g. `0204`).
     */
    data class RawEvent(
        val timestamp: Long,
        val direction: String,
        val characteristicUuid: String,
        val hex: String,
    )

    private val buffer = CopyOnWriteArrayList<LogEntry>()
    private val rawEvents = CopyOnWriteArrayList<RawEvent>()

    fun log(tag: String, message: String) {
        buffer.add(LogEntry(System.currentTimeMillis(), tag, message))
        if (buffer.size > maxEntries) {
            buffer.removeAt(0)
        }
    }

    /**
     * Record a structured raw packet for dashboard sync. [bytes] is captured as
     * contiguous lowercase hex. Kept separate from [log] (which stays a free-form
     * developer feed) so the uploaded events have a clean, parseable shape.
     */
    fun logRaw(direction: String, characteristicUuid: String, bytes: ByteArray) {
        rawEvents.add(
            RawEvent(
                timestamp = System.currentTimeMillis(),
                direction = direction,
                characteristicUuid = characteristicUuid,
                hex = bytes.joinToString("") { "%02x".format(it) },
            ),
        )
        if (rawEvents.size > maxEntries) {
            rawEvents.removeAt(0)
        }
    }

    fun getEntries(): List<LogEntry> = buffer.toList()

    fun getRawEvents(): List<RawEvent> = rawEvents.toList()

    fun clear() {
        buffer.clear()
        rawEvents.clear()
    }

    companion object {
        /** Phone → watch (a command write). */
        const val DIRECTION_TX = "phone->watch"

        /** Watch → phone (a notification or read). */
        const val DIRECTION_RX = "watch->phone"
    }

    fun format(): String {
        return buffer.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(entry.timestamp))
            "[$time] ${entry.tag}: ${entry.message}"
        }
    }
}
