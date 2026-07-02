package dev.jaredhq.dashboardandroid.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.jaredhq.dashboardandroid.MainActivity
import dev.jaredhq.dashboardandroid.R
import dev.jaredhq.dashboardandroid.domain.model.JaredCategory
import dev.jaredhq.dashboardandroid.domain.model.JaredFeedItem

/**
 * Native notification surface for the Daily Intelligence ("Jared") feed. The
 * dashboard stays the source of truth; this mirrors *active, important* feed
 * items onto the phone's shade/lock screen and (via the W7 listener) the watch.
 *
 * One channel per [JaredCategory] so the user can silence noisy categories in
 * system settings independently:
 *  - **Morning plan** (default importance) — plan ready / regenerated.
 *  - **Mid-day adjustments** (default importance) — the actionable drift nudges.
 *  - **Evening reflection** (low) — end-of-day summary.
 *  - **Plan updates** (low) — approved / scheduled / execution confirmations.
 *
 * Bodies are kept short and actionable ([watchSafeBody]) so they read well on the
 * watch. Taps open the dashboard's `/daily-intelligence` surface (deep-linked to
 * the related run when known); see [openFeedItemIntent].
 *
 * All entry points are no-ops when notifications can't be posted, so the worker
 * doesn't need to guard.
 */
object JaredNotifier {

    const val CHANNEL_MORNING = "jared_morning"
    const val CHANNEL_MIDDAY = "jared_midday"
    const val CHANNEL_EVENING = "jared_evening"
    const val CHANNEL_RESULT = "jared_result"

    // Kept clear of the reminder (2000+) and quote (1001) id spaces in [Notifier].
    private const val JARED_ID_BASE = 3000

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MORNING,
                context.getString(R.string.channel_jared_morning_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_jared_morning_desc) },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MIDDAY,
                context.getString(R.string.channel_jared_midday_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_jared_midday_desc) },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENING,
                context.getString(R.string.channel_jared_evening_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_jared_evening_desc) },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_jared_result_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_jared_result_desc) },
        )
    }

    // Posting is gated by canPostNotifications() (explicit POST_NOTIFICATIONS check
    // on API 33+); lint can't follow that guard across the call, so notify() is
    // suppressed narrowly here.
    @SuppressLint("MissingPermission")
    fun notifyFeedItem(context: Context, item: JaredFeedItem, baseUrl: String) {
        if (!canPostNotifications(context)) return
        val channel = channelFor(item.category)
        val body = watchSafeBody(item)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(context.getColor(R.color.brand_accent))
            .setContentTitle(item.title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priorityFor(item.category))
            .setContentIntent(openFeedItemIntent(context, item, baseUrl))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(JARED_ID_BASE + (item.id.toInt() and 0x3FFFFFF), n)
    }

    private fun channelFor(category: JaredCategory): String = when (category) {
        JaredCategory.MORNING -> CHANNEL_MORNING
        JaredCategory.MIDDAY -> CHANNEL_MIDDAY
        JaredCategory.EVENING -> CHANNEL_EVENING
        // RESULT — and any never-posted PREFERENCE/INSIGHT that slips through —
        // lands on the calm "plan updates" channel.
        JaredCategory.RESULT, JaredCategory.PREFERENCE, JaredCategory.INSIGHT -> CHANNEL_RESULT
    }

    private fun priorityFor(category: JaredCategory): Int = when (category) {
        JaredCategory.MORNING, JaredCategory.MIDDAY -> NotificationCompat.PRIORITY_DEFAULT
        else -> NotificationCompat.PRIORITY_LOW
    }

    /**
     * A short, actionable body suited to a watch glance. Prefers the server's body
     * (first line, trimmed/clamped); falls back to a per-category call to action so
     * a body-less item still tells the user what to do.
     */
    fun watchSafeBody(item: JaredFeedItem): String {
        val serverBody = item.body
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val text = serverBody ?: defaultCta(item.category)
        return if (text.length > WATCH_BODY_MAX) {
            text.take(WATCH_BODY_MAX - 1).trimEnd() + "…"
        } else {
            text
        }
    }

    private fun defaultCta(category: JaredCategory): String = when (category) {
        JaredCategory.MORNING -> "Review today's plan."
        JaredCategory.MIDDAY -> "Tap to review Jared's suggestion."
        JaredCategory.EVENING -> "See today's reflection."
        JaredCategory.RESULT -> "Open Daily Intelligence."
        JaredCategory.PREFERENCE -> "Jared updated a preference."
        JaredCategory.INSIGHT -> "See today's insight."
    }

    /**
     * Tap target: the dashboard's `/daily-intelligence` page, deep-linked to the
     * related run when known (`?runId=`), opened in the browser/PWA over the
     * Tailscale origin. Falls back to opening the app's Today tab when no base URL
     * is configured (so the tap is never a dead end).
     */
    private fun openFeedItemIntent(
        context: Context,
        item: JaredFeedItem,
        baseUrl: String,
    ): PendingIntent {
        val deepLink = dashboardDeepLink(baseUrl, item.relatedRunId, item.id)
        val intent = if (deepLink != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_START_ROUTE, "today")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        return PendingIntent.getActivity(
            context,
            // Distinct per item so concurrent notifications don't share/overwrite
            // each other's PendingIntent extras.
            JARED_ID_BASE + (item.id.toInt() and 0x3FFFFFF),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Build `<origin>/daily-intelligence[?runId=&feedItemId=]` from a configured
     * base URL, or null when none is set. The base URL is already normalized to an
     * origin with a trailing slash by ApiClientFactory, but we tolerate either form.
     */
    internal fun dashboardDeepLink(baseUrl: String, runId: Long?, feedItemId: Long): String? {
        val origin = baseUrl.trim().trimEnd('/')
        if (origin.isEmpty()) return null
        val query = buildList {
            if (runId != null) add("runId=$runId")
            add("feedItemId=$feedItemId")
        }.joinToString("&")
        return "$origin/daily-intelligence?$query"
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private const val WATCH_BODY_MAX = 80
}
