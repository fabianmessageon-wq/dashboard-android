package dev.jaredhq.dashboardandroid.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms the always-on watch link ([WatchConnectionService]) after the OS kills it, so calls/texts
 * keep mirroring without the user first reopening the app (W7 finish).
 *
 * Two events take down the running service: a **reboot** (`BOOT_COMPLETED`) and an **app update**
 * (`MY_PACKAGE_REPLACED`, which restarts the process and drops the foreground service). Both are
 * handled here by deferring to [WatchConnectionService.syncRunState], which self-gates on the mirror
 * opt-in (notification access) + a configured dashboard and either (re)starts or stops the service —
 * so a user who never enabled mirroring pays nothing, and the receiver needs no gating logic of its
 * own.
 *
 * **Why starting a foreground service from here is legal:** the service is a `connectedDevice` FGS,
 * and on Android 14/15 the only types a `BOOT_COMPLETED` receiver may *not* launch are `dataSync`,
 * `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, and `microphone` — `connectedDevice` is
 * not among them. `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` are also explicit exemptions to the general
 * Android 12+ background-FGS-start restriction. `BLUETOOTH_CONNECT` (which the `connectedDevice` type
 * requires) persists across reboots, and the user must have granted it to reach the mirroring state
 * in the first place, so the start won't trip a `SecurityException` on a device that was mirroring.
 *
 * `BOOT_COMPLETED` is delivered only after the user first unlocks, so the credential-encrypted
 * settings storage [syncRunState] reads is available. Any failure is swallowed — a broadcast receiver
 * must never throw during boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!handles(intent.action)) return
        Log.i(TAG, "re-arming watch link after ${intent.action}")
        runCatching { WatchConnectionService.syncRunState(context) }
            .onFailure { Log.w(TAG, "syncRunState failed", it) }
    }

    companion object {
        private const val TAG = "WatchBootReceiver"

        /** The boot/update broadcasts this receiver acts on; anything else is ignored. */
        fun handles(action: String?): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
    }
}
