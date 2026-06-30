package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.domain.model.DailyIntelligenceSettings
import dev.jaredhq.dashboardandroid.domain.model.FeedItemStatus
import dev.jaredhq.dashboardandroid.notify.JaredFeedState
import dev.jaredhq.dashboardandroid.notify.JaredNotifier

/**
 * Periodic bridge worker for the Daily Intelligence ("Jared") feed: pulls today's
 * agent feed and surfaces *new, active, important* items as native notifications.
 *
 * Filtering policy (read-only — no actions are taken on the user's behalf):
 *  - only `active` items (resolved/dismissed never notify),
 *  - only items the user's settings allow (master push switch + per-category
 *    toggles — see [DailyIntelligenceSettings.allows]),
 *  - each item at most once, via [JaredFeedState] (id-keyed, per-day).
 *
 * Idempotent, so running several times a day (the feed gains items as the day
 * unfolds) never spams. Fully degrades: notifications off, not configured, or
 * network down ⇒ returns success (nothing to surface), with a retry only for
 * transient feed failures so a missed window is caught with backoff.
 */
class JaredFeedWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        ServiceLocator.init(ctx)

        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return Result.success()

        val baseUrl = ServiceLocator.settings.baseUrlSnapshot()
        val configured = baseUrl.isNotBlank() && !ServiceLocator.settings.tokenSnapshot().isNullOrBlank()
        if (!configured) return Result.success()

        // A failed settings fetch degrades to server defaults (notifications on) so
        // a not-yet-deployed/older settings endpoint doesn't silence the feed.
        val settingsResult = ServiceLocator.repository.getDailyIntelligenceSettings()
        val settings = settingsResult.getOrNull() ?: DailyIntelligenceSettings()

        JaredNotifier.ensureChannels(ctx)
        val state = JaredFeedState(ctx)

        val feedResult = ServiceLocator.repository.getJaredFeed().onSuccess { feed ->
            // Oldest-first so notifications post in the order Jared created them.
            for (item in feed.items.sortedBy { it.id }) {
                if (item.status != FeedItemStatus.ACTIVE) continue
                if (!settings.allows(item.category)) continue
                if (state.alreadyNotified(feed.date, item.id)) continue
                JaredNotifier.notifyFeedItem(ctx, item, baseUrl)
                state.markNotified(feed.date, item.id)
            }
        }

        // A transient failure (no network, timeout, 5xx) may have skipped items that
        // should have surfaced — retry with backoff. Auth/4xx won't self-heal, so
        // treat those as success (the user must fix the token / the route).
        return if (isTransient(feedResult) || isTransient(settingsResult)) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun isTransient(result: kotlin.Result<*>): Boolean {
        val api = result.exceptionOrNull() as? ApiException ?: return false
        if (api.isAuthError) return false
        return api.status == 0 || api.status in 500..599
    }
}
