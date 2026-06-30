package dev.jaredhq.dashboardandroid.watch.music

import dev.jaredhq.dashboardandroid.watch.engine.WatchMusicControlEvent
import dev.jaredhq.dashboardandroid.watch.engine.WatchNowPlaying
import dev.jaredhq.dashboardandroid.watch.engine.WatchPlaybackState

/** Pure policy helpers kept outside Android callbacks so the edge cases have JVM coverage. */
internal object WatchMusicPolicy {
    enum class DispatchPlan {
        TRANSPORT,
        SEEK_THEN_PREVIOUS,
        MEDIA_KEY,
    }

    fun millisecondsToSeconds(value: Long): Int =
        (value.coerceAtLeast(0L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun playbackPriority(state: WatchPlaybackState?): Int = when (state) {
        WatchPlaybackState.PLAYING -> 3
        WatchPlaybackState.PAUSED -> 2
        WatchPlaybackState.STOPPED -> 1
        null -> 0
    }

    /** Avoid one BLE write per player position tick while still refreshing long plays and seeks. */
    fun shouldPushToWatch(
        previous: WatchNowPlaying?,
        next: WatchNowPlaying,
        elapsedSincePushMs: Long,
    ): Boolean {
        previous ?: return true
        if (previous.title != next.title ||
            previous.artist != next.artist ||
            previous.durationSeconds != next.durationSeconds ||
            previous.state != next.state
        ) {
            return true
        }
        val positionJump = kotlin.math.abs(next.positionSeconds - previous.positionSeconds)
        return elapsedSincePushMs >= POSITION_REFRESH_MS ||
            (positionJump >= SEEK_JUMP_SECONDS && elapsedSincePushMs <= SEEK_WINDOW_MS)
    }

    fun dispatchPlan(
        event: WatchMusicControlEvent,
        actions: Long,
        positionSeconds: Int,
    ): DispatchPlan {
        val requiredAction = when (event) {
            WatchMusicControlEvent.PLAY -> ACTION_PLAY
            WatchMusicControlEvent.PAUSE -> ACTION_PAUSE
            WatchMusicControlEvent.STOP -> ACTION_STOP
            WatchMusicControlEvent.NEXT -> ACTION_SKIP_TO_NEXT
            WatchMusicControlEvent.PREVIOUS -> ACTION_SKIP_TO_PREVIOUS
        }
        if (actions and requiredAction == 0L) return DispatchPlan.MEDIA_KEY
        if (event == WatchMusicControlEvent.PREVIOUS &&
            positionSeconds > PREVIOUS_RESTART_SECONDS &&
            actions and ACTION_SEEK_TO != 0L
        ) {
            return DispatchPlan.SEEK_THEN_PREVIOUS
        }
        return DispatchPlan.TRANSPORT
    }

    /**
     * True when Android's media session already reflects the requested action during the short
     * AVRCP grace period. In that case dispatching the IDO callback as well would double-apply it.
     */
    fun nativeEffectObserved(
        event: WatchMusicControlEvent,
        before: WatchNowPlaying?,
        after: WatchNowPlaying?,
    ): Boolean = when (event) {
        WatchMusicControlEvent.PLAY -> after?.state == WatchPlaybackState.PLAYING
        WatchMusicControlEvent.PAUSE -> after?.state == WatchPlaybackState.PAUSED
        WatchMusicControlEvent.STOP -> after?.state == WatchPlaybackState.STOPPED
        WatchMusicControlEvent.NEXT,
        WatchMusicControlEvent.PREVIOUS,
        -> before != null && after != null && before.trackIdentity() != after.trackIdentity()
    }

    private fun WatchNowPlaying.trackIdentity(): Triple<String, String, Int> =
        Triple(title, artist, durationSeconds)

    private const val POSITION_REFRESH_MS = 15_000L
    private const val SEEK_JUMP_SECONDS = 5
    private const val SEEK_WINDOW_MS = 2_000L
    private const val PREVIOUS_RESTART_SECONDS = 3

    // PlaybackState action values kept as primitives so this policy stays a pure JVM target.
    private const val ACTION_STOP = 1L
    private const val ACTION_PAUSE = 1L shl 1
    private const val ACTION_PLAY = 1L shl 2
    private const val ACTION_SKIP_TO_PREVIOUS = 1L shl 4
    private const val ACTION_SKIP_TO_NEXT = 1L shl 5
    private const val ACTION_SEEK_TO = 1L shl 8
}
