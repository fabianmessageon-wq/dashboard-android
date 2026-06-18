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
 * A calendar event or time block already committed for today — i.e. what the
 * day is *already* spoken for, independent of the recommended focus task. Labels
 * are tz-local strings straight off the wire; the client never parses times.
 *
 * Additive/forward-compatible: this maps from whatever agenda fields the
 * dashboard begins sending on `/today`. Server field names may still settle, so
 * the mapper is tolerant and every field has a safe default.
 */
data class TodayEvent(
    val title: String,
    /** tz-local "HH:mm" start, or null for all-day / undated blocks. */
    val startLabel: String?,
    /** tz-local "HH:mm" end, or null. */
    val endLabel: String?,
    /** Whether this is an all-day calendar entry. */
    val allDay: Boolean = false,
    /** Pre-formatted span older/provisional servers may send, e.g. "9:30–10:00". */
    val timeLabel: String? = null,
    /** Deep link into the dashboard from older/provisional payloads, or null. */
    val href: String? = null,
    /** Source/calendar label, e.g. "Work" or "Personal", or null. */
    val source: String? = null,
    /** Task id for task-owned scheduled blocks, or null. */
    val taskId: Int? = null,
    /** true = blocks focus time; false = tentative/free-time marker. */
    val busy: Boolean = true,
) {
    /**
     * Best single-line time string for compact surfaces (widget, agenda rows):
     * prefers the server's [timeLabel], else derives from start/end, else "".
     */
    val compactTime: String
        get() = when {
            !timeLabel.isNullOrBlank() -> timeLabel
            allDay -> "All day"
            startLabel != null && endLabel != null -> "$startLabel–$endLabel"
            startLabel != null -> startLabel
            else -> ""
        }
}

/**
 * The shape of today at a glance: is the day wide open or already blocked, and
 * how much of it is committed vs free. Every field defaults to a safe
 * "open/unknown" state so an older payload that carries no day-summary data still
 * renders sensibly (treated as an open day with nothing scheduled).
 */
data class TodayDaySummary(
    val freeDay: Boolean,
    val hasCalendarBlocks: Boolean,
    val committedMinutes: Int,
    val freeMinutes: Int,
    val eventCount: Int = 0,
    val nextEventLabel: String? = null,
    /** Server-formatted one-liner, e.g. "3h committed · 5h free", or null. */
    val summary: String? = null,
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
    /**
     * Calendar events / blocks already committed for today, in display order.
     * Empty when the day is open or the server hasn't sent agenda data yet.
     */
    val agenda: List<TodayEvent> = emptyList(),
    /** Day-at-a-glance summary, or null when the server sends no day-summary data. */
    val daySummary: TodayDaySummary? = null,
) {
    /** Busy (focus-blocking) committed events only — the "what's locked in" view. */
    val busyEvents: List<TodayEvent>
        get() = agenda.filter { it.busy }

    /**
     * Whether today is effectively open: the server says so, or — absent any
     * day-summary data — there are no busy committed events.
     */
    val isOpenDay: Boolean
        get() = daySummary?.let { it.freeDay || !it.hasCalendarBlocks } ?: busyEvents.isEmpty()
}

