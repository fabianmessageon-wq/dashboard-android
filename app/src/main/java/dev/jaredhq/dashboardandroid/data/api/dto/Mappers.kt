package dev.jaredhq.dashboardandroid.data.api.dto

import dev.jaredhq.dashboardandroid.domain.model.ActionState
import dev.jaredhq.dashboardandroid.domain.model.CaptureMode
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.FocusBlock
import dev.jaredhq.dashboardandroid.domain.model.FocusSession
import dev.jaredhq.dashboardandroid.domain.model.FocusStartResult
import dev.jaredhq.dashboardandroid.domain.model.Habit
import dev.jaredhq.dashboardandroid.domain.model.MainAction
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.QuoteSource
import dev.jaredhq.dashboardandroid.domain.model.Readiness
import dev.jaredhq.dashboardandroid.domain.model.ReadinessBand
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.domain.model.WidgetAction

/**
 * Pure DTO -> domain mappers. No Android/network dependencies, so they are the
 * unit-test seam for "does the wire contract decode correctly" (see
 * PayloadMappingTest). All tolerant of partial/garbage enum strings.
 */

fun ReadinessDto.toDomain(): Readiness =
    Readiness(score = score, band = ReadinessBand.fromWire(band))

fun MainActionDto.toDomain(): MainAction =
    MainAction(title = title, detail = detail, href = href, taskId = taskId)

fun FocusBlockDto.toDomain(): FocusBlock =
    FocusBlock(startLabel = startLabel, endLabel = endLabel, taskId = taskId)

fun WidgetActionDto.toDomain(): WidgetAction =
    WidgetAction(title = title, detail = detail, href = href, state = ActionState.fromWire(state))

fun HabitDto.toDomain(): Habit =
    Habit(id = id, title = title, doneToday = doneToday)

fun TodayPayloadDto.toDomain(): TodayPayload = TodayPayload(
    version = version,
    date = date,
    generatedAt = generatedAt,
    headline = headline,
    recoveryMode = recoveryMode,
    readiness = readiness.toDomain(),
    mainAction = mainAction?.toDomain(),
    focusBlock = focusBlock?.toDomain(),
    bodyAction = bodyAction.toDomain(),
    resetAction = resetAction.toDomain(),
    habits = habits.map { it.toDomain() },
    // Trust the server's count, but fall back to a recomputation if it looks
    // unset while habits clearly remain — keeps the widget badge honest.
    habitsRemaining = if (habitsRemaining == 0 && habits.any { !it.doneToday }) {
        habits.count { !it.doneToday }
    } else {
        habitsRemaining
    },
    warnings = warnings,
)

// ── Reverse mappers (domain -> DTO) ─────────────────────────────────────────
// Used only by the Room cache to serialize a snapshot back to JSON; the wire
// never receives these from the client. Keeping them here makes the DTO a
// lossless round-trip of the domain Today model.

fun Readiness.toDto(): ReadinessDto = ReadinessDto(score = score, band = band.wire)

fun MainAction.toDto(): MainActionDto =
    MainActionDto(title = title, detail = detail, href = href, taskId = taskId)

fun FocusBlock.toDto(): FocusBlockDto =
    FocusBlockDto(startLabel = startLabel, endLabel = endLabel, taskId = taskId)

fun WidgetAction.toDto(): WidgetActionDto =
    WidgetActionDto(title = title, detail = detail, href = href, state = state.wire)

fun Habit.toDto(): HabitDto = HabitDto(id = id, title = title, doneToday = doneToday)

fun TodayPayload.toDto(): TodayPayloadDto = TodayPayloadDto(
    version = version,
    date = date,
    generatedAt = generatedAt,
    headline = headline,
    recoveryMode = recoveryMode,
    readiness = readiness.toDto(),
    mainAction = mainAction?.toDto(),
    focusBlock = focusBlock?.toDto(),
    bodyAction = bodyAction.toDto(),
    resetAction = resetAction.toDto(),
    habits = habits.map { it.toDto() },
    habitsRemaining = habitsRemaining,
    warnings = warnings,
)

fun QuotePayloadDto.toDomain(): QuotePayload = QuotePayload(
    version = version,
    date = date,
    text = text,
    source = source?.let { QuoteSource(title = it.title, slug = it.slug) },
)

fun CaptureResponseDto.toDomain(): CaptureResult = CaptureResult(
    today = toTodayDto().toDomain(),
    reply = reply,
    actions = actions,
    pendingConfirmation = pendingConfirmation,
    createdTaskId = createdTaskId,
    mode = CaptureMode.fromWire(captureMode),
)

/**
 * Map the direct /capture response. The server doesn't send `captureMode` here
 * (that's a /chat field), so the mode is fixed to [CaptureMode.DIRECT] and the
 * one field it does add — `createdTaskId` — is preserved.
 */
fun CaptureResponseDto.toDirectCaptureResult(): CaptureResult = CaptureResult(
    today = toTodayDto().toDomain(),
    reply = null,
    actions = emptyList(),
    pendingConfirmation = emptyList(),
    createdTaskId = createdTaskId,
    mode = CaptureMode.DIRECT,
)

fun SessionDto.toDomain(): FocusSession = FocusSession(id = id, fireAt = fireAt)

fun FocusStartResponseDto.toDomain(): FocusStartResult = FocusStartResult(
    today = toTodayDto().toDomain(),
    session = session?.toDomain(),
)
