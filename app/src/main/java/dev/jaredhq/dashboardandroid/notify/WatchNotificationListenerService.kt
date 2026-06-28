package dev.jaredhq.dashboardandroid.notify

import android.app.Notification
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.watch.engine.WatchNotification
import dev.jaredhq.dashboardandroid.watch.engine.WatchNotificationCategory
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors incoming **calls and texts** to the watch face (W7).
 *
 * The user enables this by granting notification access (system Settings → Notification access);
 * that grant is the opt-in — this app requests no telephony/SMS permissions and reads nothing but the
 * posted notifications. We deliberately forward only calls, missed calls, and the default SMS app's
 * messages (not arbitrary app notifications) to match the feature and avoid a buzz storm.
 *
 * Delivery is **best-effort and display-only**: a notification is forwarded only when the watch link
 * is already up (the app connects on-demand — typically right after a background sync or while the
 * Watch screen is open). Answer/reject-from-watch (call control) and an always-on connection are
 * later steps; until then a call that arrives while disconnected simply isn't mirrored.
 */
class WatchNotificationListenerService : NotificationListenerService() {

    // Dedup: the same notification key is re-posted on every update (call ringing ticks, message
    // edits). Forward a given key at most once per [DEDUP_WINDOW_MS] so the watch isn't spammed.
    private val lastForwarded = ConcurrentHashMap<String, Long>()

    // Keys of in-flight incoming-call notifications, so when one is removed (answered/ended/declined)
    // we tell the watch to drop its incoming-call screen ([WatchEngine.stopIncomingCall]). A call is
    // mirrored via the dedicated call API, not the message list, so it needs an explicit "ended".
    private val callKeys = ConcurrentHashMap.newKeySet<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching {
            val mapped = map(sbn) ?: return
            val ctx = applicationContext
            ServiceLocator.init(ctx)
            val engine = ServiceLocator.watchEngine
            // On-demand connection model: only mirror when the watch is already linked.
            if (!engine.isConnected()) return

            // Track a ringing call so its removal can clear the watch's call screen. (Done before the
            // dedup gate: ring updates re-post the same key and we must not lose the key on a dedup
            // skip.)
            if (mapped.category == WatchNotificationCategory.CALL) callKeys.add(sbn.key)

            val now = System.currentTimeMillis()
            val previous = lastForwarded[sbn.key]
            if (previous != null && now - previous < DEDUP_WINDOW_MS) return
            lastForwarded[sbn.key] = now

            engine.sendNotification(mapped)
        }.onFailure { Log.w(TAG, "mirror failed", it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        lastForwarded.remove(key)
        // An incoming call ended (answered/declined/missed) — drop the watch's call screen.
        if (callKeys.remove(key)) {
            runCatching {
                ServiceLocator.init(applicationContext)
                if (ServiceLocator.watchEngine.isConnected()) ServiceLocator.watchEngine.stopIncomingCall()
            }.onFailure { Log.w(TAG, "stopIncomingCall mirror failed", it) }
        }
    }

    /** Map a posted notification to a [WatchNotification], or null to skip it. */
    private fun map(sbn: StatusBarNotification): WatchNotification? {
        if (sbn.packageName == packageName) return null // never mirror our own notifications
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null // the children carry the content

        val category = categoryFor(sbn, n) ?: return null // only calls + SMS in v1

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sender = title.ifBlank { appLabel(sbn.packageName) }

        val body = when (category) {
            WatchNotificationCategory.CALL ->
                if (title.isNotBlank()) "Incoming call · $title" else "Incoming call"
            WatchNotificationCategory.MISSED_CALL ->
                if (title.isNotBlank()) "Missed call · $title" else "Missed call"
            else -> text.ifBlank { title }
        }
        if (body.isBlank()) return null

        return WatchNotification(appName = sender, body = body, category = category)
    }

    /** Classify a notification, returning null for kinds we don't mirror in v1. */
    private fun categoryFor(sbn: StatusBarNotification, n: Notification): WatchNotificationCategory? =
        when {
            n.category == Notification.CATEGORY_CALL -> WatchNotificationCategory.CALL
            n.category == Notification.CATEGORY_MISSED_CALL -> WatchNotificationCategory.MISSED_CALL
            sbn.packageName == defaultSmsPackage() -> WatchNotificationCategory.SMS
            else -> null
        }

    private fun defaultSmsPackage(): String? =
        runCatching { Telephony.Sms.getDefaultSmsPackage(applicationContext) }.getOrNull()

    private fun appLabel(pkg: String): String = runCatching {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        const val TAG = "WatchNotifMirror"
        const val DEDUP_WINDOW_MS = 5_000L
    }
}
