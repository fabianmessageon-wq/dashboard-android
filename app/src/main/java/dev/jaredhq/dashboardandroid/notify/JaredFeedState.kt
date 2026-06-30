package dev.jaredhq.dashboardandroid.notify

import android.content.Context

/**
 * Per-day "already notified" bookkeeping for the Jared (Daily Intelligence) feed,
 * keyed on the server's stable numeric item id. Kept separate from
 * [NotificationState] (the legacy reminder/quote feed) so the two pipelines never
 * suppress each other.
 *
 * Why a per-day set keyed on id (not a single `lastSeenId` high-water mark): Jared
 * posts items as the day unfolds and the feed is newest-first, but an id-based set
 * is robust to out-of-order arrival and to items that flip status. The set resets
 * when the civil date rolls over, so it can't grow without bound.
 *
 * Plain (unencrypted) SharedPreferences — nothing here is sensitive; ids are
 * opaque counters.
 */
class JaredFeedState(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether item [id] has already been surfaced as a notification for [date]. */
    fun alreadyNotified(date: String, id: Long): Boolean {
        syncDate(date)
        return prefs.getStringSet(KEY_NOTIFIED, emptySet())!!.contains(id.toString())
    }

    fun markNotified(date: String, id: Long) {
        syncDate(date)
        // getStringSet's returned set must not be mutated — copy first.
        val updated = HashSet(prefs.getStringSet(KEY_NOTIFIED, emptySet())!!)
        updated.add(id.toString())
        // commit() (synchronous), not apply(): the "already notified" record must
        // survive process death immediately after posting, otherwise the item
        // re-notifies on the next worker run.
        prefs.edit().putStringSet(KEY_NOTIFIED, updated).commit()
    }

    /** Reset the per-day notified set when the civil date changes. */
    private fun syncDate(date: String) {
        if (prefs.getString(KEY_NOTIFIED_DATE, null) != date) {
            prefs.edit()
                .putString(KEY_NOTIFIED_DATE, date)
                .putStringSet(KEY_NOTIFIED, emptySet())
                .apply()
        }
    }

    private companion object {
        const val FILE = "jared_feed_state"
        const val KEY_NOTIFIED_DATE = "notified_date"
        const val KEY_NOTIFIED = "notified_ids"
    }
}
