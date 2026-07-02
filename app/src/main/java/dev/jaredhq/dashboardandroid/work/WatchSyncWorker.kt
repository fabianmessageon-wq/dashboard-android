package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.domain.model.NotificationKind
import dev.jaredhq.dashboardandroid.notify.NotificationState
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState
import dev.jaredhq.dashboardandroid.watch.engine.WatchNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background health sync: connects to the watch via the [WatchEngine] boundary and lets a full
 * sync run, so health data trickles up even if the user never opens the Watch tab.
 *
 * This replaces the old Phase-2 connection-telemetry upload. The engine's
 * connect path auto-runs the sync after (re)bind, and the engine's own upload listener
 * (`UploadingWatchHealthListener`) pushes the decoded records to the dashboard and owns upload
 * retry — so this worker only has to drive the connection and wait for the run to finish.
 *
 * Degrades quietly: no-ops when the app isn't configured, or when the engine is already busy
 * (the foreground UI is driving the same watch — the two must not contend for one GATT link). If
 * the watch isn't reachable it simply times out and succeeds; the next period tries again.
 */
class WatchSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        ServiceLocator.init(ctx)

        val configured = ServiceLocator.settings.baseUrlSnapshot().isNotBlank() &&
            !ServiceLocator.settings.tokenSnapshot().isNullOrBlank()
        if (!configured) return Result.success()

        val engine = ServiceLocator.watchEngine
        // Don't fight the foreground UI for the single GATT link to the watch.
        if (engine.connectionState.value != WatchEngineConnectionState.DISCONNECTED) {
            return Result.success()
        }

        // connect() drives scan → connect → (re)bind → sync; we wait until one sync run has
        // finished (state returns from SYNCING to CONNECTED, or the link drops). A watch that's
        // out of range never reaches SYNCING and falls through to the timeout.
        withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            engine.connect(ServiceLocator.watchDeviceId)
            var sawSyncing = false
            engine.connectionState
                .onEach { if (it == WatchEngineConnectionState.SYNCING) sawSyncing = true }
                .first { state ->
                    sawSyncing && (
                        state == WatchEngineConnectionState.CONNECTED ||
                            state == WatchEngineConnectionState.DISCONNECTED
                        )
                }
        }

        // While the link is still up after the sync, opportunistically push pending dashboard content
        // to the watch face (W7 notification bridge). This reuses the connection window we already
        // have — the app connects on-demand, so a sync is the reliable moment the watch is reachable.
        if (engine.isConnected()) {
            runCatching { pushPendingToWatch(engine) }
            // Same connection window: refresh the watch's weather face + schedule screen if due.
            runCatching { ServiceLocator.weatherPusher.pushIfDue() }
            runCatching { ServiceLocator.schedulePusher.pushIfDue() }
        }

        // Release the link so it can't linger and contend with the UI on the next foreground use.
        engine.disconnect()
        return Result.success()
    }

    /**
     * Best-effort: push the daily quote and the soonest actionable reminders to the connected watch.
     * Each is deduped per (server-computed) date via [NotificationState] using watch-specific flags
     * (independent of the native notification channel), and only marked "pushed" once the engine
     * actually dispatched it — so a failed send retries on the next sync. Reminders mirror the native
     * bridge's policy (EVENT/DEADLINE as they appear; standalone REMINDERs once due) and are capped
     * per run so the watch never gets a buzz storm; if a send fails (the link dropped) the loop
     * stops early.
     */
    private suspend fun pushPendingToWatch(engine: WatchEngine) {
        val state = NotificationState(applicationContext)

        ServiceLocator.repository.getQuote().onSuccess { quote ->
            if (state.quoteAlreadyPushedToWatch(quote.date)) return@onSuccess
            if (engine.sendNotification(WatchNotification(appName = "Daily quote", body = quote.text))) {
                state.markQuotePushedToWatch(quote.date)
            }
        }

        ServiceLocator.repository.getNotifications().onSuccess { feed ->
            var pushed = 0
            for (item in feed.items) {
                if (pushed >= MAX_WATCH_REMINDERS) break
                val pushable = when (item.kind) {
                    NotificationKind.EVENT, NotificationKind.DEADLINE -> true
                    // Same policy as NotificationWorker: only buzz for a reminder once it's due.
                    NotificationKind.REMINDER ->
                        item.whenEpoch != null && item.whenEpoch <= System.currentTimeMillis() / 1000
                    else -> false
                }
                if (!pushable) continue
                if (state.reminderAlreadyPushedToWatch(feed.date, item.id)) continue
                val body = item.timeLabel?.let { "$it · ${item.title}" } ?: item.title
                if (!engine.sendNotification(WatchNotification(appName = "Reminder", body = body))) {
                    break // engine stopped accepting (link likely dropped) — stop the run
                }
                state.markReminderPushedToWatch(feed.date, item.id)
                pushed++
            }
        }
    }

    companion object {
        const val WORK_NAME = "watch-sync"

        /** Budget for connect + (re)bind + a full sync run; well under WorkManager's 10-min cap. */
        private const val SYNC_TIMEOUT_MS = 6 * 60 * 1000L

        /** Cap reminders pushed to the watch per sync so it never gets a buzz storm. */
        private const val MAX_WATCH_REMINDERS = 3
    }
}
