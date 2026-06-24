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
 * Schedules [WatchSyncWorker] (a background watch **health** sync via the WatchEngine). Two cadences:
 *
 *  - [ensureScheduled]: a calm 6-hour periodic sync, so health data trickles up even if the user
 *    never reopens the Watch tab.
 *  - [syncNow]: a one-off manual trigger. Uses [ExistingWorkPolicy.REPLACE] so repeated taps
 *    coalesce into a single run.
 *
 * Both require network connectivity; the worker itself no-ops when the app isn't configured or the
 * engine is already busy, so scheduling is always safe.
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

    /** Trigger an immediate one-off background health sync. */
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
