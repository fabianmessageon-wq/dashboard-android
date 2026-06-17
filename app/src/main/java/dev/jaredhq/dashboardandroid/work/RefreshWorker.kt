package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.widget.TodayWidget

/**
 * Periodic background refresh: pulls a fresh Today, which the repository writes
 * to the offline cache, then nudges the Glance widget to recompose from it. So
 * the home-screen widget stays current without the app being opened.
 *
 * Network failures are non-fatal — [DashboardRepository.refreshToday] already
 * degrades to cache — so the worker only retries on an unexpected throwable.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        return try {
            ServiceLocator.repository.refreshToday()
            TodayWidget.updateAll(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            // Transient (e.g. process race). Let WorkManager back off and retry.
            Result.retry()
        }
    }
}
