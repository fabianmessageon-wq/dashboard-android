package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.domain.model.NotificationKind
import dev.jaredhq.dashboardandroid.notify.NotificationState
import dev.jaredhq.dashboardandroid.notify.Notifier

/**
 * Periodic bridge worker: pulls today's reminders feed + the daily quote from
 * the dashboard and surfaces them as native notifications. Idempotent via
 * [NotificationState] — each reminder posts at most once per day, and the daily
 * quote at most once per (server-computed) date — so running several times a day
 * (the feed gains items as the day unfolds) never spams.
 *
 * Anti-spam policy: only EVENT and DEADLINE items become notifications. The
 * headline/morning-brief is already delivered by the dashboard's server-side web
 * push, and habit/warning items are passive — they live in the app/widget, not
 * the shade.
 *
 * Fully degrades: if notifications are disabled, the app isn't configured, or the
 * network is down, it just returns success (nothing to surface).
 */
class NotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        ServiceLocator.init(ctx)

        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return Result.success()

        // Not connected yet (running on sample data) — nothing real to surface.
        val configured = ServiceLocator.settings.baseUrlSnapshot().isNotBlank() &&
            !ServiceLocator.settings.tokenSnapshot().isNullOrBlank()
        if (!configured) return Result.success()

        Notifier.ensureChannels(ctx)
        val state = NotificationState(ctx)

        val notificationsResult = ServiceLocator.repository.getNotifications().onSuccess { feed ->
            for (item in feed.items) {
                if (item.kind != NotificationKind.EVENT && item.kind != NotificationKind.DEADLINE) {
                    continue
                }
                if (state.reminderAlreadyShown(feed.date, item.id)) continue
                Notifier.notifyReminder(ctx, item)
                state.markReminderShown(feed.date, item.id)
            }
        }

        val quoteResult = ServiceLocator.repository.getQuote().onSuccess { quote ->
            if (!state.quoteAlreadyShown(quote.date)) {
                Notifier.notifyQuote(ctx, quote)
                state.markQuoteShown(quote.date)
            }
        }

        // A transient failure (no network, timeout, 5xx) means we may have skipped
        // a reminder/quote that should have surfaced. Ask WorkManager to retry with
        // backoff rather than silently waiting for the next scheduled window. Auth
        // failures won't fix themselves on retry, so treat those as success.
        return if (isTransient(notificationsResult) || isTransient(quoteResult)) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun isTransient(result: Result<*>): Boolean {
        val api = result.exceptionOrNull() as? ApiException ?: return false
        if (api.isAuthError) return false
        return api.status == 0 || api.status in 500..599
    }
}
