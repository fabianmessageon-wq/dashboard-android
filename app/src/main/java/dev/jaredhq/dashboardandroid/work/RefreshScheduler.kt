package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Registers the periodic [RefreshWorker]. 15 minutes is WorkManager's minimum
 * period; that keeps the widget reasonably warm without draining battery. Only
 * runs when the network is connected.
 */
object RefreshScheduler {

    private const val WORK_NAME = "today-refresh"
    private const val NOTIFY_WORK_NAME = "notifications-bridge"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Registers the periodic [NotificationWorker]. Every 3 hours is a calm
     * cadence: frequent enough to catch new events/deadlines as the day unfolds,
     * but battery-friendly. The worker is idempotent (see [NotificationWorker]),
     * so the period only bounds *latency*, never causes duplicate posts.
     */
    fun ensureNotificationsScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(3, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NOTIFY_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Trigger an immediate one-off refresh (e.g. from a widget action). */
    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }
}
