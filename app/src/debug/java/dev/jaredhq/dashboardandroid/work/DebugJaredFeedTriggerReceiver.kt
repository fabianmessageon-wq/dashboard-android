package dev.jaredhq.dashboardandroid.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Debug-only test hook: forces an immediate [JaredFeedWorker] run via adb so a
 * tester doesn't have to wait out the 30-minute periodic window.
 *
 *   adb shell am broadcast -a dev.jaredhq.dashboardandroid.DEBUG_JARED_FEED_NOW \
 *       -p dev.jaredhq.dashboardandroid -f 0x00000020
 *
 * (`-f 0x20` = FLAG_INCLUDE_STOPPED_PACKAGES, so it also wakes a force-stopped app
 * into a fresh process.)
 *
 * Like [DebugSyncTriggerReceiver], we enqueue **without** the `NetworkType.CONNECTED`
 * constraint that [RefreshScheduler.ensureJaredFeedScheduled] sets: JobScheduler
 * reports that constraint unsatisfied when the device routes through the Tailscale
 * VPN (the dashboard is Tailscale-only), so a constrained debug run would sit
 * ENQUEUED forever. The worker degrades gracefully when the network is genuinely
 * down, so an unconstrained run is the deterministic test of the fetch→notify path.
 *
 * Lives in the `debug` source set only — never ships in a release build.
 */
class DebugJaredFeedTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "DEBUG_JARED_FEED_NOW received → enqueuing unconstrained one-time JaredFeedWorker")
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<JaredFeedWorker>().build(),
        )
    }

    companion object {
        const val TAG = "DebugJaredFeedTrigger"
        private const val WORK_NAME = "jared-feed-debug"
    }
}
