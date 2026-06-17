package dev.jaredhq.dashboardandroid.data.api

import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload

/**
 * The dashboard server contract, expressed in domain terms. The rest of the app
 * depends ONLY on this interface — never on Retrofit/OkHttp — so the live client
 * ([RetrofitDashboardApiClient]) and the offline/preview client
 * ([FakeDashboardApiClient]) are interchangeable.
 *
 * Every mutating call returns a fresh [TodayPayload] (or [CaptureResult] that
 * wraps one): the server's "every mutation returns the full Today payload"
 * contract, so callers replace local state rather than patching it.
 *
 * Implementations throw [ApiException] on failure (auth, network, decode).
 */
interface DashboardApiClient {

    /** GET /api/widget/v1/today */
    suspend fun getToday(): TodayPayload

    /** GET /api/widget/v1/quote */
    suspend fun getQuote(): QuotePayload

    /** POST /api/widget/v1/habits/{id}/toggle -> fresh Today payload */
    suspend fun toggleHabit(habitId: Int): TodayPayload

    /**
     * POST /api/widget/v1/focus/start -> fresh Today payload.
     *
     * Both fields are optional: the server defaults to a 25-minute block and a
     * null task. The widget passes the [FocusBlock]/[MainAction] taskId so the
     * started session is linked to the recommended task. The server's response
     * also carries a `session` (with `fireAt`) the client can use to run its own
     * countdown; V1 ignores it and just re-reads the refreshed Today payload.
     */
    suspend fun startFocus(taskId: Int? = null, durationMinutes: Int? = null): TodayPayload

    /** POST /api/widget/v1/capture { title } -> fresh Today payload (201) */
    suspend fun capture(title: String): TodayPayload

    /** POST /api/widget/v1/chat { message } -> assistant summary + fresh Today */
    suspend fun chat(message: String): CaptureResult
}

/** Thrown by API clients. [status] is the HTTP code when known (0 otherwise). */
class ApiException(
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isAuthError: Boolean get() = status == 401 || status == 403
}
