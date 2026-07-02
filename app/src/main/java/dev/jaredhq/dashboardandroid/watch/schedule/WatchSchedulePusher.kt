package dev.jaredhq.dashboardandroid.watch.schedule

import android.content.Context
import android.util.Log
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.domain.model.NotificationKind
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.WatchScheduleEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the watch's native schedule/events screen in step with the dashboard: today's upcoming
 * timed events (and due-timed reminders) from the notifications feed become schedule entries the
 * watch lists and buzzes for on its own — unlike the transient message popups the notification
 * bridge sends. Push is a full overwrite of our slot range (engine handles delete+add), so a
 * change-detection signature is enough for dedupe: same upcoming set ⇒ no BLE traffic.
 *
 * Same call pattern as WeatherPusher: invoke [pushIfDue] from every link-is-up-and-settled
 * moment; it decides whether anything actually needs sending. Best-effort throughout.
 */
class WatchSchedulePusher(
    context: Context,
    private val engine: WatchEngine,
    private val repository: DashboardRepository,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("watch_schedule_push", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun pushIfDue(force: Boolean = false) {
        mutex.withLock {
            if (engine.supportsSchedulePush() != true) return

            val feed = repository.getNotifications().getOrNull() ?: return
            val nowSec = System.currentTimeMillis() / 1000
            val entries = feed.items
                .filter {
                    (it.kind == NotificationKind.EVENT || it.kind == NotificationKind.REMINDER) &&
                        it.whenEpoch != null && it.whenEpoch > nowSec
                }
                .sortedBy { it.whenEpoch }
                // note falls back to the title: the watch's schedule block renders the note as its
                // body text (a stored title alone shows an empty block with just the time).
                .map {
                    WatchScheduleEntry(
                        title = it.title,
                        note = it.detail?.takeIf { d -> d.isNotBlank() } ?: it.title,
                        epochSeconds = it.whenEpoch!!,
                    )
                }

            // Signature of what the watch should show; unchanged ⇒ nothing to send. Date is part
            // of it so a new day always refreshes (clearing yesterday's entries).
            val signature = feed.date + "|" +
                entries.joinToString(";") { "${it.epochSeconds}:${it.title}" }
            if (!force && prefs.getString(KEY_SIGNATURE, null) == signature) return

            if (engine.pushSchedule(entries)) {
                prefs.edit().putString(KEY_SIGNATURE, signature).apply()
                Log.i(TAG, "schedule pushed (${entries.size} upcoming, date=${feed.date})")
            }
        }
    }

    companion object {
        private const val TAG = "WatchSchedulePusher"
        private const val KEY_SIGNATURE = "last_signature"
    }
}
