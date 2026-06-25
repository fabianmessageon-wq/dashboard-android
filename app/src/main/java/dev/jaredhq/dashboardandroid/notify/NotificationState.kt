package dev.jaredhq.dashboardandroid.notify

import android.content.Context

/**
 * Tiny non-secret bookkeeping for the notification bridge: which reminders have
 * already been surfaced today, and whether the daily quote has been shown for a
 * given date. Plain (unencrypted) SharedPreferences — nothing here is sensitive,
 * and keeping it out of the encrypted settings file avoids churn on the secret
 * store. The per-day "shown" set resets automatically when the date rolls over,
 * so the worker can run several times a day without re-posting the same item.
 */
class NotificationState(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun quoteAlreadyShown(date: String): Boolean =
        prefs.getString(KEY_QUOTE_DATE, null) == date

    fun markQuoteShown(date: String) {
        // commit() (synchronous), not apply(): the dedup record must be durable
        // before the worker finishes. With async apply(), a process death after
        // the notification posts but before the write reaches disk would re-show
        // the same item on the next run.
        prefs.edit().putString(KEY_QUOTE_DATE, date).commit()
    }

    /**
     * Whether the daily quote has already been pushed to the **watch** for [date]. Tracked
     * separately from [quoteAlreadyShown] (the native-notification channel) so the watch push and the
     * phone notification are independent — showing one never suppresses the other (W7).
     */
    fun quoteAlreadyPushedToWatch(date: String): Boolean =
        prefs.getString(KEY_WATCH_QUOTE_DATE, null) == date

    fun markQuotePushedToWatch(date: String) {
        // commit() (synchronous): the dedup record must be durable before the worker finishes, like
        // markQuoteShown — otherwise a process death could re-push the same quote on the next run.
        prefs.edit().putString(KEY_WATCH_QUOTE_DATE, date).commit()
    }

    fun reminderAlreadyShown(date: String, id: String): Boolean {
        syncDate(date)
        return prefs.getStringSet(KEY_SHOWN, emptySet())!!.contains(id)
    }

    fun markReminderShown(date: String, id: String) {
        syncDate(date)
        // getStringSet's returned set must not be mutated — copy first.
        val updated = HashSet(prefs.getStringSet(KEY_SHOWN, emptySet())!!)
        updated.add(id)
        // commit() (synchronous), not apply(): see markQuoteShown — the "already
        // shown" record must survive process death immediately after notifying,
        // otherwise the reminder posts again on the next worker run.
        prefs.edit().putStringSet(KEY_SHOWN, updated).commit()
    }

    /** Reset the per-day "shown" set when the civil date changes. */
    private fun syncDate(date: String) {
        if (prefs.getString(KEY_SHOWN_DATE, null) != date) {
            prefs.edit()
                .putString(KEY_SHOWN_DATE, date)
                .putStringSet(KEY_SHOWN, emptySet())
                .apply()
        }
    }

    private companion object {
        const val FILE = "dashboard_notify_state"
        const val KEY_QUOTE_DATE = "quote_date"
        const val KEY_WATCH_QUOTE_DATE = "watch_quote_date"
        const val KEY_SHOWN_DATE = "shown_date"
        const val KEY_SHOWN = "shown_ids"
    }
}
