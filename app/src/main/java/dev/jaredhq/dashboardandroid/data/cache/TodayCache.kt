package dev.jaredhq.dashboardandroid.data.cache

import dev.jaredhq.dashboardandroid.domain.model.TodayPayload

/**
 * Offline cache for the last-known Today payload. The widget and the app render
 * this instantly on cold start, then refresh in the background — the dashboard
 * "in under 5 seconds from my phone" goal depends on never showing a blank
 * screen while the network is in flight.
 *
 * Abstracted behind an interface so previews/tests use [InMemoryTodayCache] and
 * the app uses [dev.jaredhq.dashboardandroid.data.cache.room.RoomTodayCache].
 */
interface TodayCache {
    /** Most recently cached payload, or null if nothing has been fetched yet. */
    suspend fun load(): TodayPayload?

    /** Replace the cached payload. */
    suspend fun save(payload: TodayPayload)

    /** Drop the cache (e.g. on token revoke / sign-out). */
    suspend fun clear()
}

/** Trivial cache for previews and unit tests. */
class InMemoryTodayCache(seed: TodayPayload? = null) : TodayCache {
    @Volatile private var value: TodayPayload? = seed
    override suspend fun load(): TodayPayload? = value
    override suspend fun save(payload: TodayPayload) { value = payload }
    override suspend fun clear() { value = null }
}
