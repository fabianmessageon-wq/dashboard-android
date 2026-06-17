package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

        ServiceLocator.repository.getNotifications().onSuccess { feed ->
            for (item in feed.items) {
                if (item.kind != NotificationKind.EVENT && item.kind != NotificationKind.DEADLINE) {
                    continue
                }
                if (state.reminderAlreadyShown(feed.date, item.id)) continue
                Notifier.notifyReminder(ctx, item)
                state.markReminderShown(feed.date, item.id)
            }
        }

        ServiceLocator.repository.getQuote().onSuccess { quote ->
            if (!state.quoteAlreadyShown(quote.date)) {
                Notifier.notifyQuote(ctx, quote)
                state.markQuoteShown(quote.date)
            }
        }

        return Result.success()
    }
}
