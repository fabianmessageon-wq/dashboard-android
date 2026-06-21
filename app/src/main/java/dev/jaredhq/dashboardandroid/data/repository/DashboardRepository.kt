package dev.jaredhq.dashboardandroid.data.repository

import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.data.api.DashboardApiClient
import dev.jaredhq.dashboardandroid.data.cache.TodayCache
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.FocusStartResult
import dev.jaredhq.dashboardandroid.domain.model.NotificationsPayload
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.domain.model.WatchSyncRequest
import dev.jaredhq.dashboardandroid.domain.model.WatchSyncResult

/**
 * The single data entry point for the app UI, the widget, and the refresh
 * worker. It owns the offline-first policy:
 *
 *  - Reads serve the cache immediately, then refresh from the network.
 *  - Every mutation calls the server, which returns the FULL fresh Today payload
 *    (the contract), and the repository replaces the cache with it — so the next
 *    read (app or widget) is already consistent, no patching.
 *  - All network failures are caught and surfaced as a [DataSource.error] result
 *    alongside the last-known cached payload, never as a crash.
 *
 * [apiProvider] yields the live client when a base URL + token are configured,
 * or a fake client otherwise (so the UI is usable before setup). It is a suspend
 * provider so the client can be rebuilt when settings change.
 */
class DashboardRepository(
    private val cache: TodayCache,
    private val apiProvider: suspend () -> DashboardApiClient,
) {

    /** Whether a value came from the network or the offline cache. */
    enum class DataSource { NETWORK, CACHE, NONE }

    /**
     * A read outcome. [payload] is the best data available (live or cached);
     * [error] is set when the network attempt failed but a cached/empty value is
     * still returned. [authError] lets the UI prompt for a new token specifically.
     */
    data class TodayOutcome(
        val payload: TodayPayload?,
        val source: DataSource,
        val error: String? = null,
        val authError: Boolean = false,
    )

    /** Outcome of a read-only connection probe (no cache or server mutation). */
    sealed interface ConnectionResult {
        /** GET /today succeeded. [quoteAvailable] reflects whether /quote also worked. */
        data class Connected(val date: String, val quoteAvailable: Boolean) : ConnectionResult
        /** The dashboard answered 401/403 — bad/missing/under-scoped token. */
        data object AuthFailed : ConnectionResult
        /** Couldn't reach the dashboard (bad URL, DNS, timeout, 5xx, …). */
        data class Unreachable(val message: String) : ConnectionResult
    }

    /** Last cached Today without touching the network — for the widget cold start. */
    suspend fun cachedToday(): TodayPayload? = cache.load()

    /**
     * Read-only connection probe for Settings. Exercises GET /today (and, for a
     * fuller signal, GET /quote) against the saved base URL + token. Both are
     * server-side non-mutating, and this deliberately does NOT write the cache —
     * a test against a not-yet-confirmed URL must not clobber good cached data.
     */
    suspend fun testConnection(checkQuote: Boolean = true): ConnectionResult = try {
        val api = client()
        val today = api.getToday()
        val quoteOk = !checkQuote || runCatching { api.getQuote() }.isSuccess
        ConnectionResult.Connected(date = today.date, quoteAvailable = quoteOk)
    } catch (e: ApiException) {
        if (e.isAuthError) {
            ConnectionResult.AuthFailed
        } else {
            ConnectionResult.Unreachable(e.message ?: "Could not reach the dashboard.")
        }
    }

    /**
     * Fetch the freshest Today. On success, updates the cache and returns NETWORK.
     * On failure, returns the cached payload (CACHE) or empty (NONE) with the
     * error attached.
     */
    suspend fun refreshToday(): TodayOutcome = try {
        val fresh = client().getToday()
        cache.save(fresh)
        TodayOutcome(fresh, DataSource.NETWORK)
    } catch (e: ApiException) {
        val cached = cache.load()
        TodayOutcome(
            payload = cached,
            source = if (cached != null) DataSource.CACHE else DataSource.NONE,
            error = e.message,
            authError = e.isAuthError,
        )
    }

    suspend fun getQuote(): Result<QuotePayload> = runApi { client().getQuote() }

    /** Today's reminders feed for the notification bridge (read-only, no cache). */
    suspend fun getNotifications(): Result<NotificationsPayload> =
        runApi { client().getNotifications() }

    suspend fun toggleHabit(habitId: Int): Result<TodayPayload> =
        runApi { client().toggleHabit(habitId).also { cache.save(it) } }

    suspend fun startFocus(
        taskId: Int? = null,
        durationMinutes: Int? = null,
    ): Result<FocusStartResult> =
        runApi { client().startFocus(taskId, durationMinutes).also { cache.save(it.today) } }

    suspend fun capture(title: String): Result<CaptureResult> =
        runApi { client().capture(title).also { cache.save(it.today) } }

    /**
     * Intelligent capture. Returns the assistant reply plus the fresh Today; the
     * cache is updated from the embedded payload so the widget reflects it too.
     */
    suspend fun chat(message: String): Result<CaptureResult> =
        runApi { client().chat(message).also { cache.save(it.today) } }

    /**
     * Upload watch connection/device telemetry (Phase 2). Read-only with respect
     * to the Today cache — this is a side-channel that never touches Today state.
     */
    suspend fun syncWatch(request: WatchSyncRequest): Result<WatchSyncResult> =
        runApi { client().syncWatch(request) }

    /**
     * Resolve the API client, converting any *construction* failure (e.g. a
     * malformed base URL that makes Retrofit throw) into an [ApiException] so it
     * flows through the same error handling as a network failure instead of
     * crashing the caller.
     */
    private suspend fun client(): DashboardApiClient = try {
        apiProvider()
    } catch (e: ApiException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ApiException(0, "Couldn't connect: ${e.message ?: "invalid dashboard URL"}", e)
    }

    /** Run an API call, normalizing both construction and call failures to Result. */
    private suspend inline fun <T> runApi(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: ApiException) {
        Result.failure(e)
    }
}
