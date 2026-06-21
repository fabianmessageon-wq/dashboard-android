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

    private val buffer = CopyOnWriteArrayList<LogEntry>()

    fun log(tag: String, message: String) {
        buffer.add(LogEntry(System.currentTimeMillis(), tag, message))
        if (buffer.size > maxEntries) {
            buffer.removeAt(0)
        }
    }

    fun getEntries(): List<LogEntry> = buffer.toList()

    fun clear() {
        buffer.clear()
    }

    fun format(): String {
        return buffer.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(entry.timestamp))
            "[$time] ${entry.tag}: ${entry.message}"
        }
    }
}
