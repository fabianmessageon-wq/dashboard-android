package dev.jaredhq.dashboardandroid.domain.model

/**
 * A started focus session, as returned by POST /focus/start alongside the fresh
 * Today payload. [fireAt] is an epoch-SECONDS timestamp (not millis) when the
 * block ends — the client can run its own countdown to it. V1 doesn't surface a
 * countdown yet, but the value is preserved rather than dropped.
 */
data class FocusSession(
    val id: Long,
    val fireAt: Long,
)

/**
 * Result of starting a focus block: the refreshed [today] (which the repository
 * caches) plus the [session] the server created (null if the server didn't
 * return one).
 */
data class FocusStartResult(
    val today: TodayPayload,
    val session: FocusSession?,
)
