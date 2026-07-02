package dev.jaredhq.dashboardandroid.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.jaredhq.dashboardandroid.R

/**
 * Phone-side "find my phone" alert, triggered from the watch
 * ([WatchControlEvent.FIND_PHONE_START][dev.jaredhq.dashboardandroid.watch.engine.WatchControlEvent]).
 *
 * Rings the system default ringtone on the **alarm** stream — the phone being looked for may well
 * be on silent, and alarm audio ignores the ringer switch — plus the reference app's vibrate
 * cadence (500ms buzz / 2s pause, repeating), and posts a heads-up notification whose tap and
 * "Found it" action both stop the alert. Auto-stops after [AUTO_STOP_MS], matching VeryFit's 15s
 * find-phone window, and invokes the caller's `onAutoStop` so the watch can be told to drop its
 * "finding phone" screen too.
 *
 * Owned and driven by [WatchConnectionService][dev.jaredhq.dashboardandroid.work.WatchConnectionService];
 * all methods must be called from the main thread (the service's control-event collector runs there).
 */
class FindPhoneAlerter(private val context: Context, private val stopIntent: PendingIntent) {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var onAutoStop: (() -> Unit)? = null
    private val autoStopRunnable = Runnable {
        val callback = onAutoStop
        stop()
        callback?.invoke()
    }

    var isActive: Boolean = false
        private set

    /** Start ringing/vibrating/notifying; no-op if already ringing. */
    fun start(onAutoStop: () -> Unit) {
        if (isActive) return
        isActive = true
        this.onAutoStop = onAutoStop
        startRingtone()
        startVibration()
        postNotification()
        handler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
        Log.i(TAG, "find-phone alert started")
    }

    /** Stop everything; idempotent. Does NOT talk to the watch — that's the caller's decision. */
    fun stop() {
        if (!isActive) return
        isActive = false
        onAutoStop = null
        handler.removeCallbacks(autoStopRunnable)
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        Log.i(TAG, "find-phone alert stopped")
    }

    private fun startRingtone() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "find-phone ringtone failed", it) }
    }

    private fun startVibration() {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib == null || !vib.hasVibrator()) return
            vibrator = vib
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 2000), 0))
        }.onFailure { Log.w(TAG, "find-phone vibration failed", it) }
    }

    // The notify() site is guarded by the explicit POST_NOTIFICATIONS check just above it;
    // lint can't follow the early return.
    @SuppressLint("MissingPermission")
    private fun postNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // still ring + vibrate — the notification is only the stop affordance
        }
        ensureChannel()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(context.getColor(R.color.brand_accent))
            .setContentTitle(context.getString(R.string.find_phone_title))
            .setContentText(context.getString(R.string.find_phone_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(stopIntent)
            .addAction(0, context.getString(R.string.find_phone_found_action), stopIntent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    private fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.channel_find_phone_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_find_phone_desc)
                // The alerter drives sound + vibration itself; a channel default would double up.
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        private const val TAG = "FindPhoneAlerter"
        private const val CHANNEL = "find_phone"
        private const val NOTIFICATION_ID = 1003
        private const val AUTO_STOP_MS = 15_000L
    }
}
