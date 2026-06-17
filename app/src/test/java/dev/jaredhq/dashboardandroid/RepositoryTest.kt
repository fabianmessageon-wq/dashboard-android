package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.FakeDashboardApiClient
import dev.jaredhq.dashboardandroid.data.api.FakeData
import dev.jaredhq.dashboardandroid.data.cache.InMemoryTodayCache
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository behavior with the in-memory fakes: refresh populates the cache,
 * mutations replace state with the server's fresh payload, and the cache stays
 * in sync (so the widget would see the same thing).
 */
class RepositoryTest {

    private fun repo(): Pair<DashboardRepository, FakeDashboardApiClient> {
        val client = FakeDashboardApiClient(latencyMs = 0)
        val cache = InMemoryTodayCache()
        return DashboardRepository(cache = cache, apiProvider = { client }) to client
    }

    @Test
    fun refreshPopulatesCacheFromNetwork() = runTest {
        val (repository, _) = repo()
        val outcome = repository.refreshToday()
        assertEquals(DashboardRepository.DataSource.NETWORK, outcome.source)
        assertEquals(FakeData.today.date, outcome.payload?.date)
        // Cache now warm for the widget.
        assertNotNull(repository.cachedToday())
    }

    @Test
    fun toggleHabitReplacesStateAndCache() = runTest {
        val (repository, _) = repo()
        repository.refreshToday()
        val firstHabit = FakeData.today.habits.first { !it.doneToday }

        val result = repository.toggleHabit(firstHabit.id)
        assertTrue(result.isSuccess)
        val fresh = result.getOrThrow()

        // The toggled habit flipped...
        val toggled = fresh.habits.first { it.id == firstHabit.id }
        assertTrue(toggled.doneToday)
        // ...remaining decremented...
        assertEquals(FakeData.today.habitsRemaining - 1, fresh.habitsRemaining)
        // ...and the cache reflects the same fresh payload.
        assertEquals(fresh, repository.cachedToday())
    }

    @Test
    fun chatReturnsReplyAndCachesEmbeddedToday() = runTest {
        val (repository, _) = repo()
        val result = repository.chat("Book dentist next week")
        assertTrue(result.isSuccess)
        val capture = result.getOrThrow()
        assertNotNull(capture.reply)
        assertFalse(capture.actions.isEmpty())
        assertEquals(capture.today, repository.cachedToday())
    }
}
