package dev.jaredhq.dashboardandroid.domain.model

/**
 * How the server interpreted a chat/capture message. The intelligent chat
 * endpoint can fall back to deterministic task capture when model calls are
 * disabled, so the client should not assume an assistant ran.
 */
enum class CaptureMode {
    /** Server's agent decided what the thought was (task/note/event/...). */
    ASSISTANT,

    /** AI off — the thought was saved verbatim as a task. */
    TASK_FALLBACK,

    /** Deterministic POST /capture path (always a task). */
    DIRECT,

    UNKNOWN;

    companion object {
        fun fromWire(value: String?): CaptureMode = when (value?.lowercase()) {
            "assistant" -> ASSISTANT
            "task-fallback" -> TASK_FALLBACK
            "direct" -> DIRECT
            else -> UNKNOWN
        }
    }
}

/**
 * Result of POST /capture or POST /chat. Both return the fresh [TodayPayload];
 * /chat additionally carries the assistant's one-line [reply] and the list of
 * tools it [executed]. The client replaces its Today state with [today].
 */
data class CaptureResult(
    val today: TodayPayload,
    val reply: String?,
    val actions: List<String>,
    val pendingConfirmation: List<String>,
    val createdTaskId: Int?,
    val mode: CaptureMode,
)
