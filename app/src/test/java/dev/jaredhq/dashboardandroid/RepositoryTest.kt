package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.data.api.DashboardApiClient
import dev.jaredhq.dashboardandroid.data.api.FakeDashboardApiClient
import dev.jaredhq.dashboardandroid.data.api.FakeData
import dev.jaredhq.dashboardandroid.data.cache.InMemoryTodayCache
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.FocusStartResult
import dev.jaredhq.dashboardandroid.domain.model.NotificationsPayload
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.domain.model.WatchSyncRequest
import dev.jaredhq.dashboardandroid.domain.model.WatchSyncResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository behavior with the in-memory fakes: refresh populates the cache,
 * mutations replace state with the server's fresh payload, and the cache stays
 * in sync (so the widget would see the same thing).
 */
class RepositoryTest {

    private fun repo(): Pair<DashboardRepository, FakeDashboardApiClient> {
        val client = FakeDashboardApiClient(latencyMs = 0)
        val cache = InMemoryTodayCache()
        return DashboardRepository(cache = cache, apiProvider = { client }) to client
    }

    @Test
    fun refreshPopulatesCacheFromNetwork() = runTest {
        val (repository, _) = repo()
        val outcome = repository.refreshToday()
        assertEquals(DashboardRepository.DataSource.NETWORK, outcome.source)
        assertEquals(FakeData.today.date, outcome.payload?.date)
        // Cache now warm for the widget.
        assertNotNull(repository.cachedToday())
    }

    @Test
    fun toggleHabitReplacesStateAndCache() = runTest {
        val (repository, _) = repo()
        repository.refreshToday()
        val firstHabit = FakeData.today.habits.first { !it.doneToday }

        val result = repository.toggleHabit(firstHabit.id)
        assertTrue(result.isSuccess)
        val fresh = result.getOrThrow()

        // The toggled habit flipped...
        val toggled = fresh.habits.first { it.id == firstHabit.id }
        assertTrue(toggled.doneToday)
        // ...remaining decremented...
        assertEquals(FakeData.today.habitsRemaining - 1, fresh.habitsRemaining)
        // ...and the cache reflects the same fresh payload.
        assertEquals(fresh, repository.cachedToday())
    }

    @Test
    fun startFocusReturnsSessionAndCachesToday() = runTest {
        val (repository, _) = repo()
        val result = repository.startFocus(taskId = 12, durationMinutes = 25)
        assertTrue(result.isSuccess)
        val focus = result.getOrThrow()
        // A session with a future fireAt (epoch seconds) is preserved, not dropped.
        assertNotNull(focus.session)
        assertTrue(focus.session!!.fireAt > 0)
        // Today is cached for the widget.
        assertEquals(focus.today, repository.cachedToday())
    }

    @Test
    fun directCaptureIsDirectModeWithTaskIdAndCachesToday() = runTest {
        val (repository, _) = repo()
        val result = repository.capture("Buy milk")
        assertTrue(result.isSuccess)
        val capture = result.getOrThrow()
        assertEquals(dev.jaredhq.dashboardandroid.domain.model.CaptureMode.DIRECT, capture.mode)
        assertNotNull(capture.createdTaskId)
        assertEquals(capture.today, repository.cachedToday())
    }

    @Test
    fun chatReturnsReplyAndCachesEmbeddedToday() = runTest {
        val (repository, _) = repo()
        val result = repository.chat("Book dentist next week")
        assertTrue(result.isSuccess)
        val capture = result.getOrThrow()
        assertNotNull(capture.reply)
        assertFalse(capture.actions.isEmpty())
        assertEquals(capture.today, repository.cachedToday())
    }

    // ── syncWatch (Phase 2 telemetry) ───────────────────────────────────────

    @Test
    fun syncWatchSucceedsAndEchoesDeviceId() = runTest {
        val (repository, _) = repo()
        val result = repository.syncWatch(
            WatchSyncRequest(deviceId = "f4:91:29:51:c6:45", connectionState = "connected"),
        )
        assertTrue(result.isSuccess)
        val ack = result.getOrThrow()
        assertTrue(ack.accepted)
        assertEquals("f4:91:29:51:c6:45", ack.deviceId)
    }

    @Test
    fun syncWatchSurfacesNetworkFailureAsResult() = runTest {
        val repository = DashboardRepository(
            cache = InMemoryTodayCache(),
            apiProvider = { FailingClient(status = 503, message = "down") },
        )
        val result = repository.syncWatch(
            WatchSyncRequest(deviceId = "AA:BB", connectionState = "connected"),
        )
        assertTrue(result.isFailure)
        assertEquals(503, (result.exceptionOrNull() as ApiException).status)
    }

    // ── testConnection (read-only probe) ────────────────────────────────────

    @Test
    fun testConnectionSucceedsAndDoesNotWriteCache() = runTest {
        val (repository, _) = repo()
        val result = repository.testConnection()
        assertTrue(result is DashboardRepository.ConnectionResult.Connected)
        result as DashboardRepository.ConnectionResult.Connected
        assertEquals(FakeData.today.date, result.date)
        assertTrue(result.quoteAvailable)
        // A probe must NOT clobber the cache (it may run against an unconfirmed URL).
        assertNull(repository.cachedToday())
    }

    @Test
    fun testConnectionMapsAuthErrorToAuthFailed() = runTest {
        val repository = DashboardRepository(
            cache = InMemoryTodayCache(),
            apiProvider = { FailingClient(status = 401) },
        )
        assertEquals(
            DashboardRepository.ConnectionResult.AuthFailed,
            repository.testConnection(),
        )
    }

    @Test
    fun testConnectionMapsNetworkErrorToUnreachable() = runTest {
        val repository = DashboardRepository(
            cache = InMemoryTodayCache(),
            apiProvider = { FailingClient(status = 0, message = "timeout") },
        )
        val result = repository.testConnection()
        assertTrue(result is DashboardRepository.ConnectionResult.Unreachable)
    }

    @Test
    fun testConnectionReportsQuoteUnavailableButStillConnected() = runTest {
        val repository = DashboardRepository(
            cache = InMemoryTodayCache(),
            apiProvider = { TodayOkQuoteFailsClient() },
        )
        val result = repository.testConnection()
        assertTrue(result is DashboardRepository.ConnectionResult.Connected)
        result as DashboardRepository.ConnectionResult.Connected
        assertFalse(result.quoteAvailable)
    }

    /** A client that fails every call with a given HTTP status. */
    private class FailingClient(
        private val status: Int,
        private val message: String = "boom",
    ) : DashboardApiClient {
        private fun fail(): Nothing = throw ApiException(status, message)
        override suspend fun getToday(): TodayPayload = fail()
        override suspend fun getQuote(): QuotePayload = fail()
        override suspend fun getNotifications(): NotificationsPayload = fail()
        override suspend fun toggleHabit(habitId: Int): TodayPayload = fail()
        override suspend fun startFocus(taskId: Int?, durationMinutes: Int?): FocusStartResult = fail()
        override suspend fun capture(title: String): CaptureResult = fail()
        override suspend fun chat(message: String): CaptureResult = fail()
        override suspend fun syncWatch(request: WatchSyncRequest): WatchSyncResult = fail()
        override suspend fun uploadWatchHealth(
            batch: dev.jaredhq.dashboardandroid.watch.engine.WatchHealthBatch,
        ): dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult = fail()
    }

    /** getToday works, but the quote endpoint is unavailable. */
    private class TodayOkQuoteFailsClient : DashboardApiClient {
        override suspend fun getToday(): TodayPayload = FakeData.today
        override suspend fun getQuote(): QuotePayload = throw ApiException(500, "quote down")
        override suspend fun getNotifications(): NotificationsPayload = FakeData.notifications
        override suspend fun toggleHabit(habitId: Int): TodayPayload = FakeData.today
        override suspend fun startFocus(taskId: Int?, durationMinutes: Int?): FocusStartResult =
            FocusStartResult(FakeData.today, null)
        override suspend fun capture(title: String): CaptureResult =
            throw ApiException(0, "n/a")
        override suspend fun chat(message: String): CaptureResult =
            throw ApiException(0, "n/a")
        override suspend fun syncWatch(request: WatchSyncRequest): WatchSyncResult =
            throw ApiException(0, "n/a")
        override suspend fun uploadWatchHealth(
            batch: dev.jaredhq.dashboardandroid.watch.engine.WatchHealthBatch,
        ): dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult =
            throw ApiException(0, "n/a")
    }
}
