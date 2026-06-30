package dev.jaredhq.dashboardandroid.data.api

import dev.jaredhq.dashboardandroid.domain.model.CaptureMode
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.DailyIntelligenceSettings
import dev.jaredhq.dashboardandroid.domain.model.FocusSession
import dev.jaredhq.dashboardandroid.domain.model.JaredFeed
import dev.jaredhq.dashboardandroid.domain.model.FocusStartResult
import dev.jaredhq.dashboardandroid.domain.model.NotificationsPayload
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthBatch
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthUploadResult
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
    private var nextSessionId = 5000L

    override suspend fun getToday(): TodayPayload = mutex.withLock {
        tick()
        today
    }

    override suspend fun getQuote(): QuotePayload {
        tick()
        return quotePayload
    }

    override suspend fun getNotifications(): NotificationsPayload {
        tick()
        return FakeData.notifications
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

    override suspend fun startFocus(taskId: Int?, durationMinutes: Int?): FocusStartResult =
        mutex.withLock {
            tick()
            // The real server starts a session and returns it alongside Today.
            val minutes = (durationMinutes ?: 25).toLong()
            val fireAt = System.currentTimeMillis() / 1000 + minutes * 60
            FocusStartResult(
                today = today,
                session = FocusSession(id = nextSessionId++, fireAt = fireAt),
            )
        }

    override suspend fun capture(title: String): CaptureResult = mutex.withLock {
        tick()
        // A direct capture creates a task server-side and returns Today + the
        // new task id; the visible Today change is usually just a new mainAction.
        CaptureResult(
            today = today,
            reply = null,
            actions = emptyList(),
            pendingConfirmation = emptyList(),
            createdTaskId = nextTaskId++,
            mode = CaptureMode.DIRECT,
        )
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

    override suspend fun uploadWatchHealth(batch: WatchHealthBatch): WatchHealthUploadResult {
        tick()
        // The offline fake simply acknowledges — no health data is persisted. Flag it offline so the
        // UI/logs don't report this as a real upload (see WatchHealthUploadResult.offline).
        return WatchHealthUploadResult(accepted = true, storedCount = batch.recordCount, offline = true)
    }

    override suspend fun getJaredFeed(): JaredFeed {
        tick()
        // No sample Jared feed before setup — an empty feed means the notification
        // bridge no-ops (nothing real to surface) rather than posting fake items.
        return JaredFeed(date = today.date, items = emptyList())
    }

    override suspend fun getDailyIntelligenceSettings(): DailyIntelligenceSettings {
        tick()
        return DailyIntelligenceSettings()
    }

    private suspend fun tick() {
        if (latencyMs > 0) delay(latencyMs)
    }
}
