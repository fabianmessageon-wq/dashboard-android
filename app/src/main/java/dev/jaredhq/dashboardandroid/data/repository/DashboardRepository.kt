package dev.jaredhq.dashboardandroid.data.repository

import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.data.api.DashboardApiClient
import dev.jaredhq.dashboardandroid.data.cache.TodayCache
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload

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

    /** Last cached Today without touching the network — for the widget cold start. */
    suspend fun cachedToday(): TodayPayload? = cache.load()

    /**
     * Fetch the freshest Today. On success, updates the cache and returns NETWORK.
     * On failure, returns the cached payload (CACHE) or empty (NONE) with the
     * error attached.
     */
    suspend fun refreshToday(): TodayOutcome = try {
        val fresh = apiProvider().getToday()
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

    suspend fun getQuote(): Result<QuotePayload> = runApi { apiProvider().getQuote() }

    suspend fun toggleHabit(habitId: Int): Result<TodayPayload> =
        runMutation { apiProvider().toggleHabit(habitId) }

    suspend fun startFocus(taskId: Int? = null, durationMinutes: Int? = null): Result<TodayPayload> =
        runMutation { apiProvider().startFocus(taskId, durationMinutes) }

    suspend fun capture(title: String): Result<TodayPayload> =
        runMutation { apiProvider().capture(title) }

    /**
     * Intelligent capture. Returns the assistant reply plus the fresh Today; the
     * cache is updated from the embedded payload so the widget reflects it too.
     */
    suspend fun chat(message: String): Result<CaptureResult> = try {
        val result = apiProvider().chat(message)
        cache.save(result.today)
        Result.success(result)
    } catch (e: ApiException) {
        Result.failure(e)
    }

    /** A mutation returns a fresh Today payload; persist it before returning. */
    private suspend inline fun runMutation(
        block: () -> TodayPayload,
    ): Result<TodayPayload> = try {
        val fresh = block()
        cache.save(fresh)
        Result.success(fresh)
    } catch (e: ApiException) {
        Result.failure(e)
    }

    private inline fun <T> runApi(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: ApiException) {
        Result.failure(e)
    }
}
