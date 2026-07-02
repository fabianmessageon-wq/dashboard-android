package dev.jaredhq.dashboardandroid.domain.model

/**
 * Domain model for the dashboard's notifications/reminders feed
 * (`GET /api/widget/v1/notifications`). The dashboard is the source of truth:
 * this is a read-only projection of today's events, approaching/overdue
 * deadlines, remaining habits, and the day's headline. The Android notification
 * bridge ([dev.jaredhq.dashboardandroid.notify]) decides what to actually post.
 *
 * Plain JVM types (no Android/serialization deps) — the wire format lives in
 * [dev.jaredhq.dashboardandroid.data.api.dto].
 */

/** How loud to treat an item — maps to a channel/heads-up decision on-device. */
enum class NotificationPriority(val wire: String) {
    HIGH("high"),
    NORMAL("normal"),
    LOW("low"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): NotificationPriority = when (value?.lowercase()) {
            "high" -> HIGH
            "normal" -> NORMAL
            "low" -> LOW
            else -> UNKNOWN
        }
    }
}

enum class NotificationKind(val wire: String) {
    HEADLINE("headline"),
    EVENT("event"),
    DEADLINE("deadline"),
    HABIT("habit"),
    WARNING("warning"),

    /** A standalone dashboard reminder (payload v2). */
    REMINDER("reminder"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): NotificationKind = when (value?.lowercase()) {
            "headline" -> HEADLINE
            "event" -> EVENT
            "deadline" -> DEADLINE
            "habit" -> HABIT
            "warning" -> WARNING
            "reminder" -> REMINDER
            else -> UNKNOWN
        }
    }
}

data class NotificationItem(
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val detail: String?,
    val timeLabel: String?,
    /** Epoch seconds the item is anchored to, or null for undated items. */
    val whenEpoch: Long?,
    val href: String,
    val priority: NotificationPriority,
)

data class NotificationCounts(
    val events: Int,
    val deadlines: Int,
    val habitsRemaining: Int,
    /** Standalone reminders due/overdue (payload v2; 0 from a v1 server). */
    val reminders: Int = 0,
)

data class NotificationsPayload(
    val version: Int,
    val date: String,
    val generatedAt: String,
    val headline: String,
    val items: List<NotificationItem>,
    val counts: NotificationCounts,
)
