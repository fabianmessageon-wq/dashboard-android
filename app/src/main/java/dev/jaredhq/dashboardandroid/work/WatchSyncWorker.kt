package dev.jaredhq.dashboardandroid.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.di.ServiceLocator

/**
 * Uploads the current watch connection/device telemetry to the dashboard
 * (Phase 2 — "safe dashboard metrics"; no health data).
 *
 * Triggered two ways (see [WatchSyncScheduler]):
 *  - one-off on every meaningful connection event (connect / disconnect), so the
 *    dashboard's connection history stays current, and
 *  - periodically, to refresh battery/MTU while a connection persists.
 *
 * Degrades quietly: if the app isn't configured (no base URL/token) or there is
 * no identifiable device yet, it just succeeds — there is nothing to send. A
 * transient network/5xx failure asks WorkManager to retry with backoff; an auth
 * failure won't fix itself on retry, so it is treated as success.
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

        // No identifiable device (idle, or a bare scan) — nothing to report.
        val request = ServiceLocator.watchBleManager.buildSyncRequest() ?: return Result.success()

        val result = ServiceLocator.repository.syncWatch(request)
        return if (isTransient(result)) Result.retry() else Result.success()
    }

    private fun isTransient(result: kotlin.Result<*>): Boolean {
        val api = result.exceptionOrNull() as? ApiException ?: return false
        if (api.isAuthError) return false
        return api.status == 0 || api.status in 500..599
    }

    companion object {
        const val WORK_NAME = "watch-sync"
    }
}
