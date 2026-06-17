package dev.jaredhq.dashboardandroid.domain.model

/**
 * Domain models for the dashboard "Today" view.
 *
 * These are plain Kotlin types with NO Android, network, or serialization
 * dependencies, so they can be unit-tested on a plain JVM and shared by the
 * app UI, the Glance widget, and the Room cache. The wire format lives in
 * [dev.jaredhq.dashboardandroid.data.api.dto]; mappers translate DTO -> these.
 *
 * The shape mirrors the dashboard server's versioned `WidgetTodayPayload`
 * public contract (see docs/api-contract.md). Treat any change here as a
 * contract change.
 */

/** Readiness band as classified by the server's Phase 9 intelligence engine. */
enum class ReadinessBand(val wire: String) {
    LOW("low"),
    MODERATE("moderate"),
    HIGH("high"),
    UNKNOWN("unknown");

    companion object {
        /** Lenient parse — unknown/missing bands degrade rather than crash. */
        fun fromWire(value: String?): ReadinessBand = when (value?.lowercase()) {
            "low" -> LOW
            "moderate" -> MODERATE
            "high" -> HIGH
            else -> UNKNOWN
        }
    }
}

/** State of a body/reset action tile. */
enum class ActionState(val wire: String) {
    DO("do"),
    DONE("done"),
    REST("rest"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): ActionState = when (value?.lowercase()) {
            "do" -> DO
            "done" -> DONE
            "rest" -> REST
            else -> UNKNOWN
        }
    }
}

data class Readiness(
    val score: Int,
    val band: ReadinessBand,
)

/** The single most important task to act on, or null when nothing is queued. */
data class MainAction(
    val title: String,
    val detail: String?,
    val href: String,
    val taskId: Int,
)

/** Recommended focus window. Labels are already tz-local `HH:mm` from the server. */
data class FocusBlock(
    val startLabel: String,
    val endLabel: String,
    val taskId: Int?,
)

/** A body or reset action tile (e.g. "Move your body", "Reflect"). */
data class WidgetAction(
    val title: String,
    val detail: String?,
    val href: String,
    val state: ActionState,
)

data class Habit(
    val id: Int,
    val title: String,
    val doneToday: Boolean,
)

/**
 * The full "Today" snapshot. Every server mutation (habit toggle, focus start,
 * capture, chat) returns a fresh copy of this, so the client just replaces its
 * state — no special-case patching.
 */
data class TodayPayload(
    val version: Int,
    val date: String,        // YYYY-MM-DD, effective tz
    val generatedAt: String, // ISO timestamp
    val headline: String,
    val recoveryMode: Boolean,
    val readiness: Readiness,
    val mainAction: MainAction?,
    val focusBlock: FocusBlock?,
    val bodyAction: WidgetAction,
    val resetAction: WidgetAction,
    val habits: List<Habit>,
    val habitsRemaining: Int,
    val warnings: List<String>,
)
