package dev.jaredhq.dashboardandroid.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs — the exact JSON the dashboard server sends. Kept separate from the
 * domain models so the wire format can drift (extra fields, renames) without
 * touching UI/cache code: only the mappers (see [TodayMapper]) change.
 *
 * Nullability here is defensive: the server contract pins these fields, but the
 * mapper tolerates a partial payload so a contract skew degrades instead of
 * crashing the widget.
 */
@Serializable
data class TodayPayloadDto(
    val version: Int = 0,
    val date: String = "",
    val generatedAt: String = "",
    val headline: String = "",
    val recoveryMode: Boolean = false,
    val readiness: ReadinessDto = ReadinessDto(),
    val mainAction: MainActionDto? = null,
    val focusBlock: FocusBlockDto? = null,
    val bodyAction: WidgetActionDto = WidgetActionDto(),
    val resetAction: WidgetActionDto = WidgetActionDto(),
    val habits: List<HabitDto> = emptyList(),
    val habitsRemaining: Int = 0,
    val warnings: List<String> = emptyList(),
    // Additive day-summary / agenda fields. Default empty/null so payloads that
    // predate these (or omit them) still decode unchanged.
    val agenda: List<TodayEventDto> = emptyList(),
    val daySummary: TodayDaySummaryDto? = null,
    // Additive: a short ranked list of the day's most useful tasks. Defaults to
    // empty so older payloads (mainAction-only) decode unchanged. When present,
    // mainAction === relevantTasks[0].
    val relevantTasks: List<TodayTaskDto> = emptyList(),
)

@Serializable
data class ReadinessDto(
    val score: Int = 0,
    val band: String = "unknown",
)

@Serializable
data class MainActionDto(
    val title: String = "",
    val detail: String? = null,
    val href: String = "",
    val taskId: Int = 0,
)

@Serializable
data class FocusBlockDto(
    val startLabel: String = "",
    val endLabel: String = "",
    val taskId: Int? = null,
)

@Serializable
data class WidgetActionDto(
    val title: String = "",
    val detail: String? = null,
    val href: String = "",
    val state: String = "unknown",
)

@Serializable
data class HabitDto(
    val id: Int = 0,
    val title: String = "",
    val doneToday: Boolean = false,
)

/**
 * A committed calendar event/block on `/today`. Field names are the client's
 * best guess at the additive dashboard contract; all are optional so a partial
 * or renamed payload degrades instead of failing to decode. `busy` defaults true
 * (a committed event blocks focus time unless the server says otherwise).
 */
@Serializable
data class TodayEventDto(
    val title: String = "",
    val startLabel: String? = null,
    val endLabel: String? = null,
    val allDay: Boolean = false,
    val timeLabel: String? = null,
    val href: String? = null,
    val source: String? = null,
    val taskId: Int? = null,
    val busy: Boolean = true,
)

/**
 * One of the day's "Relevant tasks" on `/today`. A privacy-safe projection: a
 * title, a human reason, routing/identity, and a few ranking hints — never task
 * notes. All fields optional/defaulted so a partial or renamed payload degrades
 * instead of failing to decode. `goalPriority` is "high" | "medium" | "low" |
 * null; an unknown value should map to UNKNOWN downstream rather than throw.
 */
@Serializable
data class TodayTaskDto(
    val title: String = "",
    val detail: String? = null,
    val href: String = "",
    val taskId: Int = 0,
    val score: Int = 0,
    val goalPriority: String? = null,
    val inProgress: Boolean = false,
)

/** Day-at-a-glance summary on `/today`. All fields optional; safe "open" defaults. */
@Serializable
data class TodayDaySummaryDto(
    val freeDay: Boolean = false,
    val hasCalendarBlocks: Boolean = false,
    val committedMinutes: Int = 0,
    val freeMinutes: Int = 0,
    val eventCount: Int = 0,
    val nextEventLabel: String? = null,
    val summary: String? = null,
)

@Serializable
data class QuotePayloadDto(
    val version: Int = 0,
    val date: String = "",
    val text: String = "",
    val source: QuoteSourceDto? = null,
)

@Serializable
data class QuoteSourceDto(
    val title: String = "",
    val slug: String = "",
)

/** Request body for POST /capture. */
@Serializable
data class CaptureRequest(val title: String)

/**
 * Request body for POST /focus/start. Both fields optional — omitted nulls mean
 * "server default" (25-minute block, no task link).
 */
@Serializable
data class FocusStartRequest(
    val taskId: Int? = null,
    val durationMinutes: Int? = null,
)

/** Request body for POST /chat. */
@Serializable
data class ChatRequest(val message: String)

/** The session the server creates on /focus/start. `fireAt` is epoch SECONDS. */
@Serializable
data class SessionDto(
    val id: Long = 0,
    val fireAt: Long = 0,
)

/**
 * Response for POST /focus/start: the full Today payload PLUS the started
 * `session`. Like [CaptureResponseDto], the Today fields are inlined by the
 * server (`{ ...todayPayload, session }`), so they are duplicated here and
 * projected back out via [toTodayDto].
 */
@Serializable
data class FocusStartResponseDto(
    val version: Int = 0,
    val date: String = "",
    val generatedAt: String = "",
    val headline: String = "",
    val recoveryMode: Boolean = false,
    val readiness: ReadinessDto = ReadinessDto(),
    val mainAction: MainActionDto? = null,
    val focusBlock: FocusBlockDto? = null,
    val bodyAction: WidgetActionDto = WidgetActionDto(),
    val resetAction: WidgetActionDto = WidgetActionDto(),
    val habits: List<HabitDto> = emptyList(),
    val habitsRemaining: Int = 0,
    val warnings: List<String> = emptyList(),
    val agenda: List<TodayEventDto> = emptyList(),
    val daySummary: TodayDaySummaryDto? = null,
    val relevantTasks: List<TodayTaskDto> = emptyList(),
    val session: SessionDto? = null,
) {
    fun toTodayDto(): TodayPayloadDto = TodayPayloadDto(
        version = version,
        date = date,
        generatedAt = generatedAt,
        headline = headline,
        recoveryMode = recoveryMode,
        readiness = readiness,
        mainAction = mainAction,
        focusBlock = focusBlock,
        bodyAction = bodyAction,
        resetAction = resetAction,
        habits = habits,
        habitsRemaining = habitsRemaining,
        warnings = warnings,
        agenda = agenda,
        daySummary = daySummary,
        relevantTasks = relevantTasks,
    )
}

/**
 * Response for POST /capture and POST /chat: the full Today payload PLUS the
 * capture metadata. Modeled with an explicit wrapper because the server inlines
 * these onto the Today payload (`{ ...todayPayload, reply, actions, ... }`).
 */
@Serializable
data class CaptureResponseDto(
    // Today fields (inlined by the server).
    val version: Int = 0,
    val date: String = "",
    val generatedAt: String = "",
    val headline: String = "",
    val recoveryMode: Boolean = false,
    val readiness: ReadinessDto = ReadinessDto(),
    val mainAction: MainActionDto? = null,
    val focusBlock: FocusBlockDto? = null,
    val bodyAction: WidgetActionDto = WidgetActionDto(),
    val resetAction: WidgetActionDto = WidgetActionDto(),
    val habits: List<HabitDto> = emptyList(),
    val habitsRemaining: Int = 0,
    val warnings: List<String> = emptyList(),
    val agenda: List<TodayEventDto> = emptyList(),
    val daySummary: TodayDaySummaryDto? = null,
    val relevantTasks: List<TodayTaskDto> = emptyList(),
    // Capture/chat metadata.
    val reply: String? = null,
    val actions: List<String> = emptyList(),
    val pendingConfirmation: List<String> = emptyList(),
    val createdTaskId: Int? = null,
    @SerialName("captureMode") val captureMode: String? = null,
) {
    /** Project just the Today portion of this response. */
    fun toTodayDto(): TodayPayloadDto = TodayPayloadDto(
        version = version,
        date = date,
        generatedAt = generatedAt,
        headline = headline,
        recoveryMode = recoveryMode,
        readiness = readiness,
        mainAction = mainAction,
        focusBlock = focusBlock,
        bodyAction = bodyAction,
        resetAction = resetAction,
        habits = habits,
        habitsRemaining = habitsRemaining,
        warnings = warnings,
        agenda = agenda,
        daySummary = daySummary,
        relevantTasks = relevantTasks,
    )
}

