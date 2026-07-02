package dev.jaredhq.dashboardandroid.domain.model

/**
 * Domain model for the dashboard's Daily Intelligence ("Jared") agent feed
 * (`GET /api/daily-intelligence/feed`). The dashboard is the source of truth:
 * Jared posts durable, structured messages about today's plan (morning plan
 * ready, mid-day adjustments, approved/scheduled results, evening reflection),
 * and the Android bridge ([dev.jaredhq.dashboardandroid.notify.JaredNotifier])
 * surfaces the *active, unseen, important* ones as native notifications.
 *
 * This is intentionally separate from the legacy widget reminders feed
 * ([NotificationsPayload]): different endpoint, different lifecycle (Jared items
 * have an active→resolved/dismissed status), and different on-device routing
 * (per-category channels, not events/deadlines).
 *
 * Plain JVM types (no Android/serialization deps) — the wire format lives in
 * [dev.jaredhq.dashboardandroid.data.api.dto.JaredFeedDto].
 */

/**
 * The kind of message Jared posted. Mirrors the server's `FeedItemType` union.
 * Each maps to a [JaredCategory] that decides the notification channel + whether
 * the per-feature settings toggle gates it. [UNKNOWN] absorbs any future server
 * type so an older app build degrades (channel = result, low importance) rather
 * than dropping the item.
 */
enum class FeedItemType(val wire: String, val category: JaredCategory) {
    MORNING_PLAN_READY("morning_plan_ready", JaredCategory.MORNING),
    PLAN_REGENERATED("plan_regenerated", JaredCategory.MORNING),
    PLAN_APPROVED("plan_approved", JaredCategory.RESULT),
    PLAN_SCHEDULED("plan_scheduled", JaredCategory.RESULT),
    EXECUTION_RESULT("execution_result", JaredCategory.RESULT),
    TRIGGER_RESULT("trigger_result", JaredCategory.RESULT),
    MIDDAY_NO_PLAN("midday_no_plan", JaredCategory.MIDDAY),
    APPROVED_NOT_SCHEDULED("approved_not_scheduled", JaredCategory.MIDDAY),
    PLAN_CONFLICT_DETECTED("plan_conflict_detected", JaredCategory.MIDDAY),
    PLAN_RUNNING_LATE("plan_running_late", JaredCategory.MIDDAY),
    FREE_TIME_DETECTED("free_time_detected", JaredCategory.MIDDAY),
    NO_PROGRESS_DETECTED("no_progress_detected", JaredCategory.MIDDAY),
    EVENING_REFLECTION_READY("evening_reflection_ready", JaredCategory.EVENING),
    CARRY_FORWARD_CREATED("carry_forward_created", JaredCategory.RESULT),
    PREFERENCE_LEARNED("preference_learned", JaredCategory.PREFERENCE),

    // Daily insight detectors (HRV, training load, habit cadence, goal risk,
    // calendar conflicts). Feed-only on the phone: they ride along with the
    // morning "plan ready" push, so notifying each would just be noise.
    HRV_INSIGHT("hrv_insight", JaredCategory.INSIGHT),
    TRAINING_LOAD_INSIGHT("training_load_insight", JaredCategory.INSIGHT),
    HABIT_CADENCE_INSIGHT("habit_cadence_insight", JaredCategory.INSIGHT),
    GOAL_RISK_INSIGHT("goal_risk_insight", JaredCategory.INSIGHT),
    CALENDAR_CONFLICT_INSIGHT("calendar_conflict_insight", JaredCategory.INSIGHT),
    UNKNOWN("unknown", JaredCategory.RESULT);

    companion object {
        fun fromWire(value: String?): FeedItemType =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * The user-facing grouping a feed item belongs to. Drives the notification
 * channel (so noisy categories can be silenced in system settings) and which
 * Daily Intelligence settings toggle gates it.
 */
enum class JaredCategory {
    /** Morning plan ready / regenerated. */
    MORNING,

    /** Mid-day monitor deviations — the actionable "your plan drifted" nudges. */
    MIDDAY,

    /** Evening reflection ready. */
    EVENING,

    /** Approved / scheduled / execution outcomes — confirmations. */
    RESULT,

    /** Preference/memory updates — passive; silent by default. */
    PREFERENCE,

    /** Deterministic daily insights (HRV/load/habits/goals/calendar) — feed-only, never pushed. */
    INSIGHT,
}

enum class FeedItemStatus(val wire: String) {
    ACTIVE("active"),
    RESOLVED("resolved"),
    DISMISSED("dismissed"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): FeedItemStatus = when (value?.lowercase()) {
            "active" -> ACTIVE
            "resolved" -> RESOLVED
            "dismissed" -> DISMISSED
            else -> UNKNOWN
        }
    }
}

data class JaredFeedItem(
    /** Server-assigned, stable, monotonic id — the dedupe key for "already seen". */
    val id: Long,
    val date: String,
    val type: FeedItemType,
    val title: String,
    val body: String?,
    val status: FeedItemStatus,
    /** The planning run this message describes, for deep-linking, or null. */
    val relatedRunId: Long?,
) {
    val category: JaredCategory get() = type.category
}

data class JaredFeed(
    val date: String,
    val items: List<JaredFeedItem>,
)

/**
 * The subset of `GET /api/daily-intelligence/settings` the Android bridge needs
 * to decide whether (and which) Jared notifications to post. Defaults match the
 * server defaults so a missing/old endpoint degrades to "notifications on".
 */
data class DailyIntelligenceSettings(
    val enabled: Boolean = true,
    val morningPlanningEnabled: Boolean = true,
    val middayMonitorEnabled: Boolean = true,
    val eveningReflectionEnabled: Boolean = false,
    val pushNotificationsEnabled: Boolean = true,
) {
    /** Whether a given category may post a notification under these settings. */
    fun allows(category: JaredCategory): Boolean {
        if (!enabled || !pushNotificationsEnabled) return false
        return when (category) {
            JaredCategory.MORNING -> morningPlanningEnabled
            JaredCategory.MIDDAY -> middayMonitorEnabled
            JaredCategory.EVENING -> eveningReflectionEnabled
            // Confirmations follow the master push switch; preference updates and
            // daily insights are silent-by-default (the dashboard feed is their surface).
            JaredCategory.RESULT -> true
            JaredCategory.PREFERENCE, JaredCategory.INSIGHT -> false
        }
    }
}
