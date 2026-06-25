package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jaredhq.dashboardandroid.di.ServiceLocator
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
 * This replaces the old Phase-2 telemetry upload (battery/MTU via `WatchBleManager`). The engine's
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

        // While the link is still up after the sync, opportunistically push the daily quote to the
        // watch face (W7 notification bridge). This reuses the connection window we already have —
        // the app connects on-demand, so a sync is the reliable moment the watch is reachable.
        if (engine.isConnected()) {
            runCatching { pushDailyQuoteToWatch(engine) }
        }

        // Release the link so it can't linger and contend with the UI on the next foreground use.
        engine.disconnect()
        return Result.success()
    }

    /**
     * Best-effort: fetch the daily quote and push it to the connected watch, at most once per
     * (server-computed) date. Independent of the native quote notification ([NotificationState]
     * tracks a separate watch flag), and only marks "pushed" once the engine actually dispatched it,
     * so a failed send retries on the next sync.
     */
    private suspend fun pushDailyQuoteToWatch(engine: WatchEngine) {
        val state = NotificationState(applicationContext)
        ServiceLocator.repository.getQuote().onSuccess { quote ->
            if (state.quoteAlreadyPushedToWatch(quote.date)) return@onSuccess
            val dispatched = engine.sendNotification(
                WatchNotification(appName = "Daily quote", body = quote.text),
            )
            if (dispatched) state.markQuotePushedToWatch(quote.date)
        }
    }

    companion object {
        const val WORK_NAME = "watch-sync"

        /** Budget for connect + (re)bind + a full sync run; well under WorkManager's 10-min cap. */
        private const val SYNC_TIMEOUT_MS = 6 * 60 * 1000L
    }
}
