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
import dev.jaredhq.dashboardandroid.notify.JaredFeedStatus
import dev.jaredhq.dashboardandroid.notify.JaredFeedStatusStore
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
 *
 * Every run records a [JaredFeedStatus] for the debug status card, so a tester can
 * tell configured-but-failing (RETRY/error) from not-configured from off-at-OS.
 */
class JaredFeedWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        ServiceLocator.init(ctx)
        val status = JaredFeedStatusStore(ctx)

        val baseUrl = ServiceLocator.settings.baseUrlSnapshot()

        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            status.record(skipped(JaredFeedStatus.Result.SKIPPED_NO_NOTIFICATIONS, baseUrl))
            return Result.success()
        }

        val configured = baseUrl.isNotBlank() && !ServiceLocator.settings.tokenSnapshot().isNullOrBlank()
        if (!configured) {
            status.record(skipped(JaredFeedStatus.Result.SKIPPED_NOT_CONFIGURED, baseUrl))
            return Result.success()
        }

        // A failed settings fetch degrades to server defaults (notifications on) so
        // a not-yet-deployed/older settings endpoint doesn't silence the feed.
        val settingsResult = ServiceLocator.repository.getDailyIntelligenceSettings()
        val settings = settingsResult.getOrNull() ?: DailyIntelligenceSettings()

        JaredNotifier.ensureChannels(ctx)
        val state = JaredFeedState(ctx)

        var fetched = -1
        var notified = 0
        val feedResult = ServiceLocator.repository.getJaredFeed().onSuccess { feed ->
            fetched = feed.items.size
            // Oldest-first so notifications post in the order Jared created them.
            for (item in feed.items.sortedBy { it.id }) {
                if (item.status != FeedItemStatus.ACTIVE) continue
                if (!settings.allows(item.category)) continue
                if (state.alreadyNotified(feed.date, item.id)) continue
                JaredNotifier.notifyFeedItem(ctx, item, baseUrl)
                state.markNotified(feed.date, item.id)
                notified++
            }
        }

        // A transient failure (no network, timeout, 5xx) may have skipped items that
        // should have surfaced — retry with backoff. Auth/4xx won't self-heal, so
        // treat those as success (the user must fix the token / the route).
        val retry = isTransient(feedResult) || isTransient(settingsResult)
        status.record(
            JaredFeedStatus(
                runAtMillis = System.currentTimeMillis(),
                result = if (retry) JaredFeedStatus.Result.RETRY else JaredFeedStatus.Result.SUCCESS,
                error = (feedResult.exceptionOrNull() ?: settingsResult.exceptionOrNull())?.let {
                    "${it.javaClass.simpleName}: ${it.message}"
                },
                fetched = fetched,
                notified = notified,
                baseUrl = baseUrl,
                settingsLoaded = settingsResult.isSuccess,
            ),
        )
        return if (retry) Result.retry() else Result.success()
    }

    private fun skipped(result: JaredFeedStatus.Result, baseUrl: String) = JaredFeedStatus(
        runAtMillis = System.currentTimeMillis(),
        result = result,
        error = null,
        fetched = -1,
        notified = 0,
        baseUrl = baseUrl,
        settingsLoaded = false,
    )

    private fun isTransient(result: kotlin.Result<*>): Boolean {
        val api = result.exceptionOrNull() as? ApiException ?: return false
        if (api.isAuthError) return false
        return api.status == 0 || api.status in 500..599
    }
}
