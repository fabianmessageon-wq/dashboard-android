package dev.jaredhq.dashboardandroid.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.jaredhq.dashboardandroid.MainActivity
import dev.jaredhq.dashboardandroid.R
import dev.jaredhq.dashboardandroid.domain.model.NotificationItem
import dev.jaredhq.dashboardandroid.domain.model.NotificationPriority
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload

/**
 * The native notification surface for dashboard data. The dashboard remains the
 * source of truth (the morning brief / reminder *scheduling* lives server-side
 * via web push); this bridge mirrors today's reminders + the daily quote onto
 * the phone's notification shade and lock screen, where Android can't be reached
 * from the web app.
 *
 * Two channels so the user can tune them independently in system settings:
 *  - **Reminders** (default importance): timed events + due/overdue deadlines.
 *  - **Daily quote** (low importance, lock-screen visible): one calm line a day.
 *
 * All entry points are no-ops when notifications are disabled, so callers
 * (the worker) don't need to guard.
 */
object Notifier {

    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_QUOTE = "quote"

    // Fixed id so the daily quote replaces yesterday's rather than stacking.
    private const val QUOTE_NOTIFICATION_ID = 1001
    // Reminder ids derive from the (stable, namespaced) server item id.
    private const val REMINDER_ID_BASE = 2000

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminders_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_reminders_desc) },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUOTE,
                context.getString(R.string.channel_quote_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_quote_desc)
                setShowBadge(false)
            },
        )
    }

    // Posting is gated by canPostNotifications() at the top, which does an explicit
    // checkSelfPermission(POST_NOTIFICATIONS) on API 33+. Lint can't follow that guard
    // across the helper call, so the notify() site is suppressed narrowly here.
    @SuppressLint("MissingPermission")
    fun notifyReminder(context: Context, item: NotificationItem) {
        if (!canPostNotifications(context)) return
        val body = listOfNotNull(item.timeLabel, item.detail)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val n = baseBuilder(context, CHANNEL_REMINDERS)
            .setContentTitle(item.title)
            .setContentText(body.ifBlank { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { item.title }))
            .setPriority(
                if (item.priority == NotificationPriority.HIGH) {
                    NotificationCompat.PRIORITY_DEFAULT
                } else {
                    NotificationCompat.PRIORITY_LOW
                },
            )
            .setContentIntent(openIntent(context, "today"))
            .setAutoCancel(true)
            .build()
        // Derive a stable per-reminder id from the server item id. A 30-bit mask
        // (not the old 15-bit one, which collided around ~180 ids) makes distinct
        // reminders practically never overwrite each other, while staying clear of
        // Int overflow when offset by REMINDER_ID_BASE.
        NotificationManagerCompat.from(context)
            .notify(REMINDER_ID_BASE + (item.id.hashCode() and 0x3FFFFFFF), n)
    }

    // See notifyReminder: the notify() site is guarded by canPostNotifications().
    @SuppressLint("MissingPermission")
    fun notifyQuote(context: Context, quote: QuotePayload) {
        if (!canPostNotifications(context)) return
        if (quote.text.isBlank()) return
        val n = baseBuilder(context, CHANNEL_QUOTE)
            .setContentTitle("Daily quote")
            .setContentText(quote.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(quote.text))
            .setSubText(quote.source?.title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Show the full text on the lock screen — the product intent of the
            // "lock-screen daily message" (Android forbids true lock-screen
            // widgets for third-party apps; a public notification is the
            // reliable native equivalent).
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent(context, "today"))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(QUOTE_NOTIFICATION_ID, n)
    }

    private fun baseBuilder(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(context.getColor(R.color.brand_accent))

    private fun openIntent(context: Context, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_START_ROUTE, route)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            route.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * True only when the app may actually post a notification. On Android 13+ this
     * requires the runtime POST_NOTIFICATIONS permission (requested in MainActivity);
     * below API 33 it is implicitly granted. We also honour the user's per-app/channel
     * toggle via areNotificationsEnabled(), so callers stay simple no-ops when off.
     */
    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
