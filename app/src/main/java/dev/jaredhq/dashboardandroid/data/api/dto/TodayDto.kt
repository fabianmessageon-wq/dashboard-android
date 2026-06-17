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
    )
}
