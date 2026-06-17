package dev.jaredhq.dashboardandroid.data.api

import dev.jaredhq.dashboardandroid.domain.model.ActionState
import dev.jaredhq.dashboardandroid.domain.model.FocusBlock
import dev.jaredhq.dashboardandroid.domain.model.Habit
import dev.jaredhq.dashboardandroid.domain.model.MainAction
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.QuoteSource
import dev.jaredhq.dashboardandroid.domain.model.Readiness
import dev.jaredhq.dashboardandroid.domain.model.ReadinessBand
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.domain.model.WidgetAction

/**
 * Deterministic sample data used for Compose previews, the offline-first UI
 * before any token is configured, and unit tests. Single source so previews and
 * the [FakeDashboardApiClient] never drift apart.
 */
object FakeData {

    val today: TodayPayload = TodayPayload(
        version = 1,
        date = "2026-06-17",
        generatedAt = "2026-06-17T07:30:00.000Z",
        headline = "Deep work on the launch proposal",
        recoveryMode = false,
        readiness = Readiness(score = 72, band = ReadinessBand.HIGH),
        mainAction = MainAction(
            title = "Deep work on the launch proposal",
            detail = "Highest-leverage task, due Thursday",
            href = "/tasks",
            taskId = 12,
        ),
        focusBlock = FocusBlock(startLabel = "09:30", endLabel = "11:00", taskId = 12),
        bodyAction = WidgetAction(
            title = "Move your body",
            detail = "No workout yet today.",
            href = "/workouts",
            state = ActionState.DO,
        ),
        resetAction = WidgetAction(
            title = "Reflect",
            detail = "Two minutes to close the day.",
            href = "/reflection",
            state = ActionState.DO,
        ),
        habits = listOf(
            Habit(id = 1, title = "Read", doneToday = false),
            Habit(id = 2, title = "Meditate", doneToday = true),
            Habit(id = 3, title = "Walk", doneToday = false),
        ),
        habitsRemaining = 2,
        warnings = listOf("3 tasks due today — consider deferring one."),
    )

    val recoveryToday: TodayPayload = today.copy(
        headline = "Recovery day — take it easy",
        recoveryMode = true,
        readiness = Readiness(score = 38, band = ReadinessBand.LOW),
        mainAction = null,
        focusBlock = null,
        bodyAction = WidgetAction(
            title = "Take it easy",
            detail = "Low readiness — light movement or rest.",
            href = "/workouts",
            state = ActionState.REST,
        ),
        warnings = listOf("Low readiness — protect your downtime."),
    )

    val quote: QuotePayload = QuotePayload(
        version = 1,
        date = "2026-06-17",
        text = "The work is the reward. Show up, do the next small thing.",
        source = QuoteSource(title = "Notes — On Craft", slug = "on-craft"),
    )
}
