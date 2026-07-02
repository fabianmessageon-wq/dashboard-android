package dev.jaredhq.dashboardandroid.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaredhq.dashboardandroid.di.ServiceLocator

/**
 * Debug-only test hook: repoint the app at a different watch MAC via adb (pairing without a
 * rebuild — e.g. a replacement Active 4 Pro, or restoring the default after a test).
 *
 *   adb shell am broadcast -a dev.jaredhq.dashboardandroid.DEBUG_SET_WATCH_MAC \
 *       -p dev.jaredhq.dashboardandroid --es mac "AA:BB:CC:DD:EE:FF" -f 0x00000020
 *
 * Omitting `--es mac` logs the currently persisted MAC without changing it. The change takes
 * effect on the next connect attempt (the connection service reads the MAC per attempt);
 * force-stop + relaunch for a clean re-pair. Lives in the `debug` source set only.
 */
class DebugSetWatchMacReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServiceLocator.init(context.applicationContext)
        val mac = intent.getStringExtra("mac")?.trim()
        if (mac.isNullOrEmpty()) {
            Log.i(TAG, "current watch MAC: ${ServiceLocator.watchDeviceId}")
            return
        }
        if (!MAC_RE.matches(mac)) {
            Log.w(TAG, "rejected malformed MAC '$mac'")
            return
        }
        ServiceLocator.watchDeviceId = mac
        Log.i(TAG, "watch MAC set to ${ServiceLocator.watchDeviceId} (takes effect on next connect)")
    }

    companion object {
        const val TAG = "DebugSetWatchMac"
        private val MAC_RE = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
