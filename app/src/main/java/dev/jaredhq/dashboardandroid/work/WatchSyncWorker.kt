package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jaredhq.dashboardandroid.di.ServiceLocator

/**
 * Background worker for syncing watch connection status to the dashboard.
 *
 * Phase 1: manual-trigger only (one-off from the Watch screen).
 * Future phases: schedule periodically to auto-upload battery, MTU, and MAC.
 */
class WatchSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        return try {
            // Phase 1: placeholder — the actual sync will read from
            // WatchBleManager.state and POST to the dashboard API.
            // For now, this worker succeeds silently so the enqueue path is wired.
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "watch-sync"
    }
}
