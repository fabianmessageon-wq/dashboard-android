package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules [WatchSyncWorker]. Two cadences:
 *
 *  - [ensureScheduled]: a calm 6-hour periodic refresh, so battery/MTU telemetry
 *    trickles up even if the user never reopens the Watch tab.
 *  - [syncNow]: a one-off, fired on connection events and from the manual "Sync"
 *    button. Uses [ExistingWorkPolicy.REPLACE] so rapid connect/disconnect churn
 *    coalesces into a single upload of the latest state rather than a backlog.
 *
 * Both require network connectivity; the worker itself no-ops when the app isn't
 * configured, so scheduling is always safe.
 */
object WatchSyncScheduler {

    private const val PERIODIC_WORK_NAME = "watch-sync-periodic"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Trigger an immediate one-off telemetry upload (connection event / manual). */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WatchSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchSyncWorker>()
                .setConstraints(networkConstraints)
                .build(),
        )
    }
}
