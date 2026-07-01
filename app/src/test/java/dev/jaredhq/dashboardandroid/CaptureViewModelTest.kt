package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.data.api.DashboardApiClient
import dev.jaredhq.dashboardandroid.data.api.FakeDashboardApiClient
import dev.jaredhq.dashboardandroid.data.api.FakeData
import dev.jaredhq.dashboardandroid.data.cache.InMemoryTodayCache
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.domain.model.CaptureMode
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.FocusStartResult
import dev.jaredhq.dashboardandroid.domain.model.NotificationsPayload
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.ui.capture.CaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CaptureViewModel behavior: the assistant-state repair (explicit mode/actions/
 * task-id/fallback), input + mode snapshotting at send start, friendly error
 * mapping, and the speech-transcript merge. Uses the in-memory fakes; the
 * viewModelScope is driven by a [StandardTestDispatcher] installed as Main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vmWith(client: DashboardApiClient): CaptureViewModel {
        val repo = DashboardRepository(cache = InMemoryTodayCache(), apiProvider = { client })
        return CaptureViewModel(repo)
    }

    @Test
    fun assistantSuccessStoresReplyActionsModeAndClearsInput() = runTest(dispatcher) {
        val vm = vmWith(FakeDashboardApiClient(latencyMs = 0))
        vm.onInputChange("Meeting with Sam Friday 2pm")

        vm.send()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.sending)
        assertEquals("", s.input)
        assertEquals(CaptureMode.ASSISTANT, s.lastMode)
        assertNotNull(s.lastReply)
        assertTrue(s.lastActions.isNotEmpty())
        assertEquals("Assistant handled this.", s.statusMessage)
        assertTrue(s.hasResult)
        assertNull(s.error)
    }

    @Test
    fun useAssistantIsSnapshottedAtSendStart() = runTest(dispatcher) {
        val vm = vmWith(FakeDashboardApiClient(latencyMs = 0))
        vm.setUseAssistant(true)
        vm.onInputChange("Book dentist next week")

        vm.send()                  // snapshots useAssistant = true
        vm.setUseAssistant(false)  // coroutine body not run yet (sending still false) → applies

        advanceUntilIdle()

        // The in-flight request used the snapshot, not the later toggle.
        assertEquals(CaptureMode.ASSISTANT, vm.state.value.lastMode)
        // But the toggle itself took effect for the next send.
        assertFalse(vm.state.value.useAssistant)
    }

    @Test
    fun directCaptureShowsTaskSavedWithIdAndClearsInput() = runTest(dispatcher) {
        val vm = vmWith(FakeDashboardApiClient(latencyMs = 0))
        vm.setUseAssistant(false)
        vm.onInputChange("Buy milk")

        vm.send()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(CaptureMode.DIRECT, s.lastMode)
        assertNotNull(s.lastCreatedTaskId)
        assertTrue(s.statusMessage!!.startsWith("Saved as a task"))
        assertEquals("", s.input)
        assertNull(s.error)
    }

    @Test
    fun taskFallbackIsShownExplicitly() = runTest(dispatcher) {
        val vm = vmWith(FallbackChatClient())
        vm.onInputChange("Meeting Friday 2pm")

        vm.send()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(CaptureMode.TASK_FALLBACK, s.lastMode)
        assertTrue(s.statusMessage!!.contains("AI is off"))
        assertEquals(9, s.lastCreatedTaskId)
    }

    @Test
    fun authErrorMapsToFriendlyMessageWithoutLeakingInternals() = runTest(dispatcher) {
        val vm = vmWith(FailingClient(status = 401, message = "dwtk_secret_internal_value"))
        vm.onInputChange("anything")

        vm.send()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.sending)
        assertNotNull(s.error)
        assertFalse(s.error!!.contains("dwtk_secret_internal_value"))
        assertTrue(s.error!!.contains("Settings"))
        assertNull(s.statusMessage)
    }

    @Test
    fun assistantUnavailableMapsTo502FriendlyMessage() = runTest(dispatcher) {
        val vm = vmWith(FailingClient(status = 502, message = "Bad Gateway"))
        vm.onInputChange("Plan the offsite")

        vm.send()
        advanceUntilIdle()

        assertTrue(vm.state.value.error!!.contains("assistant is unavailable"))
    }

    @Test
    fun applyTranscriptAppendsToExistingInput() {
        val vm = vmWith(FakeDashboardApiClient(latencyMs = 0))
        vm.onInputChange("Buy")
        vm.applyTranscript("milk today")
        assertEquals("Buy milk today", vm.state.value.input)
    }

    @Test
    fun applyTranscriptIntoEmptyInputTrimsAndSets() {
        val vm = vmWith(FakeDashboardApiClient(latencyMs = 0))
        vm.applyTranscript("  call the dentist  ")
        assertEquals("call the dentist", vm.state.value.input)
    }

    // ── Test doubles ────────────────────────────────────────────────────────────

    /** /chat returns the deterministic task-fallback shape (AI off). */
    private class FallbackChatClient : DashboardApiClient {
        override suspend fun getToday(): TodayPayload = FakeData.today
        override suspend fun getQuote(): QuotePayload = FakeData.quote
        override suspend fun getNotifications(): NotificationsPayload = FakeData.notifications
        override suspend fun toggleHabit(habitId: Int): TodayPayload = FakeData.today
        override suspend fun startFocus(taskId: Int?, durationMinutes: Int?): FocusStartResult =
            FocusStartResult(FakeData.today, null)
        override suspend fun capture(title: String): CaptureResult =
            CaptureResult(FakeData.today, null, emptyList(), emptyList(), 7, CaptureMode.DIRECT)
        override suspend fun chat(message: String): CaptureResult = CaptureResult(
            today = FakeData.today,
            reply = "AI is off — saved it as a task.",
            actions = listOf("create_task"),
            pendingConfirmation = emptyList(),
            createdTaskId = 9,
            mode = CaptureMode.TASK_FALLBACK,
        )
        override suspend fun uploadWatchHealthDto(
            dto: dev.jaredhq.dashboardandroid.data.api.dto.WatchHealthUploadDto,
        ): dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult =
            dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult(accepted = true)
        override suspend fun getJaredFeed() =
            dev.jaredhq.dashboardandroid.domain.model.JaredFeed(date = "", items = emptyList())
        override suspend fun getDailyIntelligenceSettings() =
            dev.jaredhq.dashboardandroid.domain.model.DailyIntelligenceSettings()
    }

    /** Every call fails with a given HTTP status (message must not leak to the UI). */
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
        override suspend fun uploadWatchHealthDto(
            dto: dev.jaredhq.dashboardandroid.data.api.dto.WatchHealthUploadDto,
        ): dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult = fail()
        override suspend fun getJaredFeed(): dev.jaredhq.dashboardandroid.domain.model.JaredFeed = fail()
        override suspend fun getDailyIntelligenceSettings():
            dev.jaredhq.dashboardandroid.domain.model.DailyIntelligenceSettings = fail()
    }
}
