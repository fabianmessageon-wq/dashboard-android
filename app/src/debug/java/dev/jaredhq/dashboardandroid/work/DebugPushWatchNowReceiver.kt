package dev.jaredhq.dashboardandroid.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only test hook: force an immediate weather + schedule push over the EXISTING link via adb
 * (bypasses the rate limit / change signature). Unlike DEBUG_SYNC_NOW this doesn't need the engine
 * to be DISCONNECTED — it's the deterministic way to test pushes while the always-on service
 * holds the connection.
 *
 *   adb shell am broadcast -a dev.jaredhq.dashboardandroid.DEBUG_PUSH_WATCH_NOW \
 *       -p dev.jaredhq.dashboardandroid -f 0x00000020
 *
 * Lives in the `debug` source set only.
 */
class DebugPushWatchNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServiceLocator.init(context.applicationContext)
        Log.i(TAG, "DEBUG_PUSH_WATCH_NOW → forcing weather + schedule push")
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            runCatching { ServiceLocator.weatherPusher.pushIfDue(force = true) }
                .onFailure { Log.w(TAG, "weather push failed", it) }
            runCatching { ServiceLocator.schedulePusher.pushIfDue(force = true) }
                .onFailure { Log.w(TAG, "schedule push failed", it) }
        }
    }

    companion object {
        const val TAG = "DebugPushWatchNow"
    }
}
