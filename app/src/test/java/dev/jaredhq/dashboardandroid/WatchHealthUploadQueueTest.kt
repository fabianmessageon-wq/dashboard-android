package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.dto.WatchHealthUploadDto
import dev.jaredhq.dashboardandroid.data.api.dto.WatchSleepSessionDto
import dev.jaredhq.dashboardandroid.data.repository.WatchHealthUploadQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The durable spool for failed watch-health uploads. File-backed, so these run as plain JVM tests
 * against a [TemporaryFolder]. Only synthetic sample values are used.
 */
class WatchHealthUploadQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dto(date: String) = WatchHealthUploadDto(
        deviceId = "AA:BB:CC:DD:EE:FF",
        sleepSessions = listOf(
            WatchSleepSessionDto(date = date, totalMinutes = 400, remMinutes = 80, avgHeartRate = 52),
        ),
    )

    @Test
    fun enqueueThenPendingRoundTripsTheDto() {
        val queue = WatchHealthUploadQueue(tmp.newFolder("q1"))
        queue.enqueue(dto("2026-06-28"))

        val pending = queue.pending()
        assertEquals(1, pending.size)
        assertEquals(1, queue.size())
        val sleep = pending.single().dto.sleepSessions.single()
        assertEquals("2026-06-28", sleep.date)
        assertEquals(80, sleep.remMinutes)
        assertEquals(52, sleep.avgHeartRate)
    }

    @Test
    fun removeDeletesTheEntry() {
        val queue = WatchHealthUploadQueue(tmp.newFolder("q2"))
        queue.enqueue(dto("2026-06-28"))
        queue.remove(queue.pending().single())

        assertEquals(0, queue.size())
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun pendingIsOldestFirst() {
        val queue = WatchHealthUploadQueue(tmp.newFolder("q3"))
        queue.enqueue(dto("2026-06-26"))
        queue.enqueue(dto("2026-06-27"))
        queue.enqueue(dto("2026-06-28"))

        val dates = queue.pending().map { it.dto.sleepSessions.single().date }
        assertEquals(listOf("2026-06-26", "2026-06-27", "2026-06-28"), dates)
    }

    @Test
    fun corruptFileIsDroppedNotReturned() {
        val dir = tmp.newFolder("q4")
        val queue = WatchHealthUploadQueue(dir)
        queue.enqueue(dto("2026-06-28"))
        // Drop a non-JSON file into the spool alongside a valid one.
        java.io.File(dir, "0000000000000-9999.json").writeText("{ not valid json")

        val pending = queue.pending()
        assertEquals(1, pending.size) // only the good one survives
        assertEquals("2026-06-28", pending.single().dto.sleepSessions.single().date)
        assertEquals(1, queue.size()) // the corrupt file was deleted
    }

    @Test
    fun boundedToMaxEntriesDroppingOldest() {
        val queue = WatchHealthUploadQueue(tmp.newFolder("q5"), maxEntries = 2)
        queue.enqueue(dto("2026-06-26"))
        queue.enqueue(dto("2026-06-27"))
        queue.enqueue(dto("2026-06-28")) // pushes the oldest out

        val dates = queue.pending().map { it.dto.sleepSessions.single().date }
        assertEquals(listOf("2026-06-27", "2026-06-28"), dates)
    }
}
