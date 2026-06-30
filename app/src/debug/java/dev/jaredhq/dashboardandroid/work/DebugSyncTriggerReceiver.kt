package dev.jaredhq.dashboardandroid.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Debug-only test hook: forces an immediate background [WatchSyncWorker] run via adb.
 *
 *   adb shell am broadcast -a dev.jaredhq.dashboardandroid.DEBUG_SYNC_NOW \
 *       -p dev.jaredhq.dashboardandroid -f 0x00000020
 *
 * (`-f 0x20` = FLAG_INCLUDE_STOPPED_PACKAGES, so it also wakes a force-stopped app into a fresh
 * process — giving the worker a `DISCONNECTED` engine to drive.)
 *
 * Why not reuse [WatchSyncScheduler.syncNow]: that enqueues with a `NetworkType.CONNECTED`
 * constraint, which JobScheduler reports unsatisfied when the device routes through the Tailscale
 * VPN (the dashboard is Tailscale-only), so the worker sits ENQUEUED forever. The worker→engine
 * connect is BLE, not network; the upload listener owns its own networking. So for a deterministic
 * test we enqueue **without** constraints to exercise the worker-driven connect→sync path now.
 *
 * Lives in the `debug` source set only — never ships in a release build.
 */
class DebugSyncTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "DEBUG_SYNC_NOW received → enqueuing unconstrained one-time WatchSyncWorker")
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchSyncWorker>().build(),
        )
    }

    companion object {
        const val TAG = "DebugSyncTrigger"
        private const val WORK_NAME = "watch-sync-debug"
    }
}
