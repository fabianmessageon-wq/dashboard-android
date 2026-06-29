package dev.jaredhq.dashboardandroid.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.jaredhq.dashboardandroid.R
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.notify.NotificationAccess
import dev.jaredhq.dashboardandroid.watch.engine.WatchControlEvent
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Always-on foreground service that keeps the watch linked so incoming calls/texts mirror in real
 * time (W7). Without it the app only connects on-demand, so a call arriving while disconnected
 * couldn't reach the watch.
 *
 * Gated on the **notification-access grant** (the mirror opt-in) plus a configured dashboard: it runs
 * only when both hold, and self-stops otherwise, so users who haven't enabled mirroring never pay the
 * battery/ongoing-notification cost. It maintains a single connection with auto-reconnect (backoff)
 * and triggers a periodic health sync so always-on doesn't starve the [WatchSyncWorker] path (which
 * no-ops while this service holds the one GATT link).
 *
 * Start it from a foreground context ([startIfEnabled] in `MainActivity.onResume`) to satisfy
 * Android 12+ limits on starting a foreground service from the background.
 */
class WatchConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!started) {
            started = true
            scope.launch { maintain() }
        }
        return START_STICKY
    }

    private suspend fun maintain() {
        val ctx = applicationContext
        ServiceLocator.init(ctx)
        val engine = ServiceLocator.watchEngine

        // Periodic health sync while connected, so an always-on link doesn't starve the sync path.
        scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                if (engine.connectionState.value == WatchEngineConnectionState.CONNECTED) {
                    runCatching { engine.syncHealth() }
                }
            }
        }

        // Perform call actions the watch initiates (answer/reject/mute) — W7 call control.
        scope.launch { engine.controlEvents.collect { handleControl(it) } }

        while (coroutineContext.isActive) {
            if (!shouldRun(ctx)) {
                stopMirroring(engine)
                return
            }
            if (engine.connectionState.value == WatchEngineConnectionState.DISCONNECTED) {
                Log.i(TAG, "maintaining link → connect")
                engine.connect(ServiceLocator.watchDeviceId)
                // Let the attempt move past DISCONNECTED before we wait for a drop (connect() sets
                // SCANNING synchronously); bounded so a no-op connect can't wedge the loop.
                withTimeoutOrNull(CONNECT_SETTLE_MS) {
                    engine.connectionState.first { it != WatchEngineConnectionState.DISCONNECTED }
                }
            }
            // Suspend until the link drops (stays suspended forever while it's up), then back off.
            // A connect that wedges mid-attempt (stuck CONNECTING/SCANNING with no SDK callback) no
            // longer hangs this forever: the engine's connect watchdog forces such an attempt to
            // DISCONNECTED, so this resumes and the loop reconnects. (See IdoSdkWatchEngine.)
            engine.connectionState.first { it == WatchEngineConnectionState.DISCONNECTED }
            delay(RECONNECT_BACKOFF_MS)
        }
    }

    // ── Call control (watch → phone) ──────────────────────────────────────────────────

    private fun handleControl(event: WatchControlEvent) {
        Log.i(TAG, "control event from watch: $event")
        when (event) {
            WatchControlEvent.ANSWER_CALL -> answerCall()
            WatchControlEvent.REJECT_CALL -> rejectCall()
            WatchControlEvent.MUTE_CALL -> muteRinger()
        }
    }

    private fun hasAnswerPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    // Guarded by hasAnswerPermission(); lint can't see across the helper.
    @SuppressLint("MissingPermission")
    private fun answerCall() {
        if (!hasAnswerPermission()) {
            Log.w(TAG, "answer ignored — ANSWER_PHONE_CALLS not granted")
            return
        }
        runCatching { getSystemService(TelecomManager::class.java)?.acceptRingingCall() }
            .onFailure { Log.w(TAG, "answer failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun rejectCall() {
        if (!hasAnswerPermission()) {
            Log.w(TAG, "reject ignored — ANSWER_PHONE_CALLS not granted")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "reject needs API 28+ (TelecomManager.endCall)")
            return
        }
        runCatching { getSystemService(TelecomManager::class.java)?.endCall() }
            .onFailure { Log.w(TAG, "reject failed", it) }
    }

    // silenceRinger needs MODIFY_PHONE_STATE (system/signature) which a normal app can't hold, so
    // lint flags it. The call is intentionally best-effort and runCatching-guarded below, matching
    // answerCall/rejectCall above.
    @SuppressLint("MissingPermission")
    private fun muteRinger() {
        // Best-effort: silenceRinger needs default-dialer/system rights on most devices, so it may
        // no-op. Answer/reject are the primary controls.
        runCatching { getSystemService(TelecomManager::class.java)?.silenceRinger() }
            .onFailure { Log.w(TAG, "mute failed", it) }
    }

    /** Run only while mirroring is enabled (notification access) and the app is configured. */
    private fun shouldRun(ctx: Context): Boolean =
        NotificationAccess.isGranted(ctx) &&
            ServiceLocator.settings.baseUrlSnapshot().isNotBlank() &&
            !ServiceLocator.settings.tokenSnapshot().isNullOrBlank()

    private fun stopMirroring(engine: WatchEngine) {
        Log.i(TAG, "mirroring disabled — releasing link + stopping")
        runCatching { engine.disconnect() }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { ServiceLocator.watchEngine.disconnect() }
        scope.cancel()
        started = false
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(getColor(R.color.brand_accent))
            .setContentTitle("Watch connected")
            .setContentText("Keeping your watch linked to mirror calls and texts.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.channel_watch_link_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_watch_link_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "WatchConnService"
        private const val CHANNEL = "watch_link"
        private const val NOTIF_ID = 1002
        private const val SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6h
        private const val CONNECT_SETTLE_MS = 20_000L
        private const val RECONNECT_BACKOFF_MS = 60_000L

        /**
         * Start the always-on connection if mirroring is enabled (notification access granted) and
         * the app is configured; otherwise stop it. Safe to call repeatedly (onStartCommand guards).
         * MUST be called from a foreground context to satisfy Android 12+ background-FGS-start limits.
         */
        fun syncRunState(context: Context) {
            ServiceLocator.init(context.applicationContext)
            val configured = ServiceLocator.settings.baseUrlSnapshot().isNotBlank() &&
                !ServiceLocator.settings.tokenSnapshot().isNullOrBlank()
            val intent = Intent(context, WatchConnectionService::class.java)
            if (configured && NotificationAccess.isGranted(context)) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
