package dev.jaredhq.dashboardandroid.watch.music

import android.content.ComponentName
import dev.jaredhq.dashboardandroid.watch.engine.WatchNowPlaying
import kotlinx.coroutines.flow.StateFlow

/** State shared by the notification-listener service and the Watch screen. */
data class PhoneMusicState(
    val enabled: Boolean = false,
    val notificationListenerConnected: Boolean = false,
    val nowPlaying: WatchNowPlaying? = null,
)

/** App-owned phone media-session boundary. No vendor SDK types are allowed here. */
interface WatchMusicController {
    val state: StateFlow<PhoneMusicState>

    fun setEnabled(enabled: Boolean)

    /** Called only from the system-bound notification-listener service. */
    fun start(notificationListener: ComponentName)

    fun stop()
}
