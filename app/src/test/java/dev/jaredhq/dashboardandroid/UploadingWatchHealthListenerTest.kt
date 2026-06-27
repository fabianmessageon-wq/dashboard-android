package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.data.api.DashboardApiClient
import dev.jaredhq.dashboardandroid.data.cache.InMemoryTodayCache
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.FocusStartResult
import dev.jaredhq.dashboardandroid.domain.model.NotificationsPayload
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.watch.engine.UploadingWatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchBloodPressureReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthBatch
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult
import dev.jaredhq.dashboardandroid.watch.engine.WatchSpo2Reading
import dev.jaredhq.dashboardandroid.watch.engine.WatchStressReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchUploadOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UploadingWatchHealthListener] buffer→flush→upload behaviour with a faked repository.
 *
 * The listener decodes nothing itself: it queues whatever records the engine delivers and, on the
 * end-of-sync lifecycle callbacks, uploads one batch and reports a [WatchUploadOutcome]. These tests
 * fake the network via a real [DashboardRepository] over a stub [DashboardApiClient] (no Android
 * deps, no contortion of production architecture), and drive the listener's `scope.launch` with a
 * [StandardTestDispatcher] sharing the `runTest` scheduler. Only synthetic, content-free sample
 * values are used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadingWatchHealthListenerTest {

    /** A client that records the uploaded batch and returns/throws a configurable result. */
    private class StubClient(
        private val onUpload: (WatchHealthBatch) -> WatchHealthUploadResult,
    ) : DashboardApiClient {
        var lastBatch: WatchHealthBatch? = null
        var uploadCalls = 0

        override suspend fun uploadWatchHealth(batch: WatchHealthBatch): WatchHealthUploadResult {
            uploadCalls++
            lastBatch = batch
            return onUpload(batch)
        }

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")
        override suspend fun getToday(): TodayPayload = unused()
        override suspend fun getQuote(): QuotePayload = unused()
        override suspend fun getNotifications(): NotificationsPayload = unused()
        override suspend fun toggleHabit(habitId: Int): TodayPayload = unused()
        override suspend fun startFocus(taskId: Int?, durationMinutes: Int?): FocusStartResult = unused()
        override suspend fun capture(title: String): CaptureResult = unused()
        override suspend fun chat(message: String): CaptureResult = unused()
    }

    private fun repoOver(client: DashboardApiClient) =
        DashboardRepository(cache = InMemoryTodayCache(), apiProvider = { client })

    // Compact synthetic records (few fields) covering the two metrics the composite previously dropped.
    private val spo2 = WatchSpo2Reading(recordedAt = "2026-06-24 10:00:00", percent = 97)
    private val bp = WatchBloodPressureReading(recordedAt = "2026-06-24 10:01:00", systolic = 120, diastolic = 80)
    private val stress = WatchStressReading(recordedAt = "2026-06-24 10:02:00", stressScore = 40)

    @Test
    fun completeFlushesAndReportsSuccessCountsIncludingBpAndStress() = runTest {
        val client = StubClient { WatchHealthUploadResult(accepted = true, storedCount = 3) }
        val outcomes = mutableListOf<WatchUploadOutcome>()
        val listener = UploadingWatchHealthListener(
            repository = repoOver(client),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            deviceId = "AA:BB:CC:DD:EE:FF",
            onUploadOutcome = { outcomes += it },
        )

        listener.onSpo2Reading(spo2)
        listener.onBloodPressureReading(bp)
        listener.onStressReading(stress)
        listener.onSyncComplete()
        advanceUntilIdle()

        val outcome = outcomes.single()
        assertTrue(outcome.succeeded)
        assertEquals(3, outcome.sentCount)
        assertEquals(3, outcome.storedCount)
        assertNull(outcome.error)

        // The blood-pressure + stress buffers (the previously-dropped metrics) reach the batch.
        val batch = requireNotNull(client.lastBatch)
        assertEquals(1, batch.bloodPressureReadings.size)
        assertEquals(1, batch.stressReadings.size)
        assertEquals(3, batch.recordCount)
    }

    @Test
    fun offlineSinkIsNotReportedAsSuccessfulUpload() = runTest {
        // No dashboard configured → the fake/offline client acks without persisting. The outcome
        // must NOT read as a successful upload (the bug that hid an hour of un-persisted syncs).
        val client = StubClient {
            WatchHealthUploadResult(accepted = true, storedCount = 3, offline = true)
        }
        val outcomes = mutableListOf<WatchUploadOutcome>()
        val listener = UploadingWatchHealthListener(
            repository = repoOver(client),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            deviceId = "AA:BB:CC:DD:EE:FF",
            onUploadOutcome = { outcomes += it },
        )

        listener.onSpo2Reading(spo2)
        listener.onSyncComplete()
        advanceUntilIdle()

        val outcome = outcomes.single()
        assertFalse(outcome.succeeded)
        assertTrue(outcome.offline)
        assertEquals(1, outcome.sentCount)
    }

    @Test
    fun completeReportsFailureWithErrorWhenUploadThrows() = runTest {
        val client = StubClient { throw ApiException(500, "server boom") }
        val outcomes = mutableListOf<WatchUploadOutcome>()
        val listener = UploadingWatchHealthListener(
            repository = repoOver(client),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            deviceId = "AA:BB:CC:DD:EE:FF",
            onUploadOutcome = { outcomes += it },
        )

        listener.onSpo2Reading(spo2)
        listener.onSyncComplete()
        advanceUntilIdle()

        val outcome = outcomes.single()
        assertFalse(outcome.succeeded)
        assertEquals(1, outcome.sentCount)
        assertEquals(0, outcome.storedCount)
        assertEquals("server boom", outcome.error)
    }

    @Test
    fun emptySyncDoesNotUploadOrReport() = runTest {
        val client = StubClient { WatchHealthUploadResult(accepted = true, storedCount = 0) }
        val outcomes = mutableListOf<WatchUploadOutcome>()
        val listener = UploadingWatchHealthListener(
            repository = repoOver(client),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            deviceId = "AA:BB:CC:DD:EE:FF",
            onUploadOutcome = { outcomes += it },
        )

        // No records buffered → flush is a no-op: no network call, no outcome.
        listener.onSyncComplete()
        advanceUntilIdle()

        assertTrue(outcomes.isEmpty())
        assertEquals(0, client.uploadCalls)
        assertNull(client.lastBatch)
    }

    @Test
    fun failedSyncStillFlushesBufferedRecords() = runTest {
        // This watch often reports a benign end-of-sync failure *after* delivering a full dataset;
        // onSyncFailed must still upload what was buffered rather than dropping it.
        val client = StubClient { WatchHealthUploadResult(accepted = true, storedCount = 2) }
        val outcomes = mutableListOf<WatchUploadOutcome>()
        val listener = UploadingWatchHealthListener(
            repository = repoOver(client),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            deviceId = "AA:BB:CC:DD:EE:FF",
            onUploadOutcome = { outcomes += it },
        )

        listener.onBloodPressureReading(bp)
        listener.onStressReading(stress)
        listener.onSyncFailed()
        advanceUntilIdle()

        assertEquals(1, client.uploadCalls)
        val outcome = outcomes.single()
        assertTrue(outcome.succeeded)
        assertEquals(2, outcome.sentCount)
    }
}
