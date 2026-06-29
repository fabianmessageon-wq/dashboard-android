package dev.jaredhq.dashboardandroid.watch.engine

/** Engine-neutral playback state pushed from the phone to the watch. */
enum class WatchPlaybackState {
    PLAYING,
    PAUSED,
    STOPPED,
}

/** Current phone track. Metadata is private user data and must never be written to logs. */
data class WatchNowPlaying(
    val title: String,
    val artist: String,
    val state: WatchPlaybackState,
    val positionSeconds: Int,
    val durationSeconds: Int,
)

/** Music action delivered by the watch over the IDO control callback. */
enum class WatchMusicControlEvent {
    PLAY,
    PAUSE,
    STOP,
    NEXT,
    PREVIOUS,
}

/** Music-related flags from the connected watch's function table. */
data class WatchMusicCapabilities(
    val known: Boolean = false,
    val phoneMusicControl: Boolean = false,
    val artistName: Boolean = false,
    val onboardMusic: Boolean = false,
)
