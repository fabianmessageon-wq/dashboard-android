package dev.jaredhq.dashboardandroid.notify

import android.content.Context

/**
 * Outcome of the most recent [dev.jaredhq.dashboardandroid.work.JaredFeedWorker]
 * run, persisted so a tester can diagnose background/Tailscale/auth failures
 * without attaching logcat. Surfaced by the debug-only status card on the Settings
 * screen.
 *
 * Purely diagnostic; nothing here is privacy-sensitive (ids are opaque counters,
 * `baseUrl` is the user's own tailnet host, `error` is a class/message string with
 * no payload). Written on every run, so "Never run" vs a stale timestamp is itself
 * a signal that the periodic worker isn't firing.
 */
data class JaredFeedStatus(
    /** Epoch millis of the run, or 0 if the worker has never run on this install. */
    val runAtMillis: Long,
    val result: Result,
    /** Exception class + message when the feed/settings fetch failed, else null. */
    val error: String?,
    /** Items returned by the feed (before filtering), or -1 when the fetch didn't run. */
    val fetched: Int,
    /** Items newly surfaced as notifications this run. */
    val notified: Int,
    /** Base URL the run used (empty when not configured). */
    val baseUrl: String,
    /** True when settings were fetched from the server; false when they defaulted. */
    val settingsLoaded: Boolean,
) {
    enum class Result {
        /** Fetch + filter completed; [notified] items posted. */
        SUCCESS,

        /** Transient feed/settings failure — WorkManager will retry with backoff. */
        RETRY,

        /** Notifications are disabled at the OS level (channel/permission off). */
        SKIPPED_NO_NOTIFICATIONS,

        /** No base URL or no device token — nothing to fetch. */
        SKIPPED_NOT_CONFIGURED,
    }
}

/**
 * Plain (unencrypted) SharedPreferences persistence for [JaredFeedStatus]. Separate
 * file from [JaredFeedState] so clearing one (e.g. resetting dedupe) never wipes the
 * other's diagnostics.
 */
class JaredFeedStatusStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(status: JaredFeedStatus) {
        prefs.edit()
            .putLong(KEY_RUN_AT, status.runAtMillis)
            .putString(KEY_RESULT, status.result.name)
            .putString(KEY_ERROR, status.error)
            .putInt(KEY_FETCHED, status.fetched)
            .putInt(KEY_NOTIFIED, status.notified)
            .putString(KEY_BASE_URL, status.baseUrl)
            .putBoolean(KEY_SETTINGS_LOADED, status.settingsLoaded)
            .apply()
    }

    /** The last recorded status, or null if the worker has never run on this install. */
    fun read(): JaredFeedStatus? {
        val runAt = prefs.getLong(KEY_RUN_AT, 0L)
        if (runAt == 0L) return null
        return JaredFeedStatus(
            runAtMillis = runAt,
            result = runCatching {
                JaredFeedStatus.Result.valueOf(prefs.getString(KEY_RESULT, null) ?: "")
            }.getOrDefault(JaredFeedStatus.Result.SUCCESS),
            error = prefs.getString(KEY_ERROR, null),
            fetched = prefs.getInt(KEY_FETCHED, -1),
            notified = prefs.getInt(KEY_NOTIFIED, 0),
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            settingsLoaded = prefs.getBoolean(KEY_SETTINGS_LOADED, false),
        )
    }

    private companion object {
        const val FILE = "jared_feed_status"
        const val KEY_RUN_AT = "run_at"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_FETCHED = "fetched"
        const val KEY_NOTIFIED = "notified"
        const val KEY_BASE_URL = "base_url"
        const val KEY_SETTINGS_LOADED = "settings_loaded"
    }
}
