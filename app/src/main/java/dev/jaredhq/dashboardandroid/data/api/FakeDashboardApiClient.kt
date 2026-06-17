package dev.jaredhq.dashboardandroid.data.api

import dev.jaredhq.dashboardandroid.domain.model.CaptureMode
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation used for:
 *  - Compose previews and the very first launch (before a token is set),
 *  - local UI work with no dashboard server running,
 *  - unit tests of the repository/UI without the network.
 *
 * Mutations behave like the real server: each one updates the in-memory Today
 * state and returns the FULL fresh payload (the "every mutation returns Today"
 * contract), so screens exercise the same replace-state code path as live.
 *
 * [latencyMs] simulates network delay; set 0 in tests for determinism.
 */
class FakeDashboardApiClient(
    initial: TodayPayload = FakeData.today,
    private val quotePayload: QuotePayload = FakeData.quote,
    private val latencyMs: Long = 250L,
) : DashboardApiClient {

    private val mutex = Mutex()
    private var today: TodayPayload = initial
    private var nextTaskId = 1000

    override suspend fun getToday(): TodayPayload = mutex.withLock {
        tick()
        today
    }

    override suspend fun getQuote(): QuotePayload {
        tick()
        return quotePayload
    }

    override suspend fun toggleHabit(habitId: Int): TodayPayload = mutex.withLock {
        tick()
        val habits = today.habits.map {
            if (it.id == habitId) it.copy(doneToday = !it.doneToday) else it
        }
        today = today.copy(
            habits = habits,
            habitsRemaining = habits.count { !it.doneToday },
        )
        today
    }

    override suspend fun startFocus(taskId: Int?, durationMinutes: Int?): TodayPayload =
        mutex.withLock {
            tick()
            // The real server starts a session; the Today payload itself doesn't
            // change much, so just echo current state with a benign warning drop.
            today = today.copy(warnings = today.warnings)
            today
        }

    override suspend fun capture(title: String): TodayPayload = mutex.withLock {
        tick()
        // A direct capture creates a task server-side; the visible Today change
        // is usually just a possible new mainAction. Keep it simple/deterministic.
        today
    }

    override suspend fun chat(message: String): CaptureResult = mutex.withLock {
        tick()
        CaptureResult(
            today = today,
            reply = "Saved \"${message.take(40)}\" for you.",
            actions = listOf("create_task"),
            pendingConfirmation = emptyList(),
            createdTaskId = nextTaskId++,
            mode = CaptureMode.ASSISTANT,
        )
    }

    private suspend fun tick() {
        if (latencyMs > 0) delay(latencyMs)
    }
}
