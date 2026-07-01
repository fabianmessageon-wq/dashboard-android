package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.data.api.DashboardApiClient
import dev.jaredhq.dashboardandroid.data.api.dto.WatchHealthUploadDto
import dev.jaredhq.dashboardandroid.data.cache.InMemoryTodayCache
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.data.repository.WatchHealthUploadQueue
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.DailyIntelligenceSettings
import dev.jaredhq.dashboardandroid.domain.model.FocusStartResult
import dev.jaredhq.dashboardandroid.domain.model.JaredFeed
import dev.jaredhq.dashboardandroid.domain.model.NotificationsPayload
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthBatch
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult
import dev.jaredhq.dashboardandroid.watch.engine.WatchSleepSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The loss-safe watch-upload path: a failed POST is spooled to [WatchHealthUploadQueue] and re-sent
 * later instead of being dropped (the BLE sync + listener flush have already cleared both buffers,
 * so without this the sync is gone). Uses a stub client whose response is swappable per test.
 */
class DashboardRepositoryWatchRetryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A client whose watch-upload response is controlled by [responder] (which may throw). Every
     *  upload path in the interface funnels through [uploadWatchHealthDto]. */
    private class StubClient : DashboardApiClient {
        var responder: (WatchHealthUploadDto) -> WatchHealthUploadResult = { accepted() }
        var dtoCalls = 0

        override suspend fun uploadWatchHealthDto(dto: WatchHealthUploadDto): WatchHealthUploadResult {
            dtoCalls++
            return responder(dto)
        }

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")
        override suspend fun getToday(): TodayPayload = unused()
        override suspend fun getQuote(): QuotePayload = unused()
        override suspend fun getNotifications(): NotificationsPayload = unused()
        override suspend fun toggleHabit(habitId: Int): TodayPayload = unused()
        override suspend fun startFocus(taskId: Int?, durationMinutes: Int?): FocusStartResult = unused()
        override suspend fun capture(title: String): CaptureResult = unused()
        override suspend fun chat(message: String): CaptureResult = unused()
        override suspend fun getJaredFeed(): JaredFeed = unused()
        override suspend fun getDailyIntelligenceSettings(): DailyIntelligenceSettings = unused()

        companion object {
            fun accepted() = WatchHealthUploadResult(accepted = true, storedCount = 1)
            fun offline() = WatchHealthUploadResult(accepted = true, storedCount = 1, offline = true)
        }
    }

    private fun batch(date: String) = WatchHealthBatch(
        deviceId = "AA:BB:CC:DD:EE:FF",
        sleepSessions = listOf(
            WatchSleepSession(
                date = date,
                totalMinutes = 400,
                deepMinutes = 100,
                lightMinutes = 220,
                awakeMinutes = 0,
                deepCount = 4,
                lightCount = 8,
                awakeCount = 1,
                score = 88,
                sleepEndHour = 6,
                sleepEndMinute = 30,
                remMinutes = 80,
                avgHeartRate = 52,
            ),
        ),
    )

    private fun repo(client: DashboardApiClient, queue: WatchHealthUploadQueue) =
        DashboardRepository(cache = InMemoryTodayCache(), apiProvider = { client }, watchUploadQueue = queue)

    @Test
    fun failedUploadIsSpooledThenDrainedOnRetry() = runTest {
        val queue = WatchHealthUploadQueue(tmp.newFolder("q"))
        val client = StubClient().apply { responder = { throw ApiException(500, "server boom") } }
        val repository = repo(client, queue)

        // The POST fails → the batch is spooled rather than lost.
        assertTrue(repository.uploadWatchHealth(batch("2026-06-28")).isFailure)
        assertEquals(1, queue.size())

        // Connection recovers → the backlog drains and the spool empties.
        client.responder = { StubClient.accepted() }
        assertEquals(1, repository.retryPendingWatchHealth())
        assertEquals(0, queue.size())
    }

    @Test
    fun offlineAckDoesNotDrainTheSpool() = runTest {
        val queue = WatchHealthUploadQueue(tmp.newFolder("q"))
        val client = StubClient().apply { responder = { throw ApiException(0, "no dashboard") } }
        val repository = repo(client, queue)
        repository.uploadWatchHealth(batch("2026-06-28"))
        assertEquals(1, queue.size())

        // An offline/fake ack is NOT a real upload — the entry must stay queued.
        client.responder = { StubClient.offline() }
        assertEquals(0, repository.retryPendingWatchHealth())
        assertEquals(1, queue.size())
    }

    @Test
    fun nextSuccessfulUploadDrainsPriorBacklogFirst() = runTest {
        val queue = WatchHealthUploadQueue(tmp.newFolder("q"))
        val client = StubClient().apply { responder = { throw ApiException(500, "boom") } }
        val repository = repo(client, queue)
        repository.uploadWatchHealth(batch("2026-06-27")) // spooled
        assertEquals(1, queue.size())

        // A later sync succeeds: it drains the backlog AND uploads the new batch, leaving nothing.
        client.responder = { StubClient.accepted() }
        assertTrue(repository.uploadWatchHealth(batch("2026-06-28")).isSuccess)
        assertEquals(0, queue.size())
    }

    @Test
    fun noQueueKeepsFireAndForgetBehaviour() = runTest {
        // Without a queue (previews/tests), a failure is surfaced but nothing is spooled.
        val client = StubClient().apply { responder = { throw ApiException(500, "boom") } }
        val repository = DashboardRepository(cache = InMemoryTodayCache(), apiProvider = { client })
        assertTrue(repository.uploadWatchHealth(batch("2026-06-28")).isFailure)
        assertEquals(0, repository.retryPendingWatchHealth())
        assertFalse(client.dtoCalls == 0) // the upload was still attempted
    }
}
