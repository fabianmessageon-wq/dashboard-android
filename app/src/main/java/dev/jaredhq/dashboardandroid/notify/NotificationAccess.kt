package dev.jaredhq.dashboardandroid.notify

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Helpers for the notification-access grant that powers [WatchNotificationListenerService] (W7).
 *
 * There is no programmatic way to request notification access — the user must toggle it in system
 * Settings — so the app can only check the current state and deep-link to the right settings screen.
 */
object NotificationAccess {

    /** Whether this app currently holds notification-listener access. */
    fun isGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** Intent to the system "Notification access" screen so the user can grant/revoke. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
