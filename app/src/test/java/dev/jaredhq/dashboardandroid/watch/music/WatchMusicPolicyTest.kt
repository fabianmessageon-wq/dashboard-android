package dev.jaredhq.dashboardandroid.watch.music

import dev.jaredhq.dashboardandroid.watch.engine.WatchMusicControlEvent
import dev.jaredhq.dashboardandroid.watch.engine.WatchNowPlaying
import dev.jaredhq.dashboardandroid.watch.engine.WatchPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMusicPolicyTest {
    @Test
    fun convertsAndClampsMilliseconds() {
        assertEquals(0, WatchMusicPolicy.millisecondsToSeconds(-1L))
        assertEquals(61, WatchMusicPolicy.millisecondsToSeconds(61_999L))
        assertEquals(Int.MAX_VALUE, WatchMusicPolicy.millisecondsToSeconds(Long.MAX_VALUE))
    }

    @Test
    fun playingSessionHasHighestPriority() {
        assertTrue(
            WatchMusicPolicy.playbackPriority(WatchPlaybackState.PLAYING) >
                WatchMusicPolicy.playbackPriority(WatchPlaybackState.PAUSED),
        )
        assertTrue(
            WatchMusicPolicy.playbackPriority(WatchPlaybackState.PAUSED) >
                WatchMusicPolicy.playbackPriority(null),
        )
    }

    @Test
    fun nativeStateChangeSuppressesDuplicateGattControl() {
        val paused = track(state = WatchPlaybackState.PAUSED)
        val playing = paused.copy(state = WatchPlaybackState.PLAYING)

        assertTrue(
            WatchMusicPolicy.nativeEffectObserved(WatchMusicControlEvent.PLAY, paused, playing),
        )
        assertFalse(
            WatchMusicPolicy.nativeEffectObserved(WatchMusicControlEvent.NEXT, paused, playing),
        )
        assertTrue(
            WatchMusicPolicy.nativeEffectObserved(
                WatchMusicControlEvent.NEXT,
                playing,
                playing.copy(title = "Next track"),
            ),
        )
    }

    @Test
    fun suppressesPositionTicksButAllowsSeekAndPeriodicRefresh() {
        val original = track(state = WatchPlaybackState.PLAYING)

        assertFalse(
            WatchMusicPolicy.shouldPushToWatch(
                original,
                original.copy(positionSeconds = 11),
                elapsedSincePushMs = 1_000,
            ),
        )
        assertTrue(
            WatchMusicPolicy.shouldPushToWatch(
                original,
                original.copy(positionSeconds = 40),
                elapsedSincePushMs = 1_000,
            ),
        )
        assertTrue(
            WatchMusicPolicy.shouldPushToWatch(
                original,
                original.copy(positionSeconds = 25),
                elapsedSincePushMs = 15_000,
            ),
        )
    }

    @Test
    fun choosesTransportOnlyWhenSessionAdvertisesAction() {
        val previousAndSeek = (1L shl 4) or (1L shl 8)

        assertEquals(
            WatchMusicPolicy.DispatchPlan.MEDIA_KEY,
            WatchMusicPolicy.dispatchPlan(WatchMusicControlEvent.NEXT, previousAndSeek, 20),
        )
        assertEquals(
            WatchMusicPolicy.DispatchPlan.SEEK_THEN_PREVIOUS,
            WatchMusicPolicy.dispatchPlan(WatchMusicControlEvent.PREVIOUS, previousAndSeek, 20),
        )
        assertEquals(
            WatchMusicPolicy.DispatchPlan.TRANSPORT,
            WatchMusicPolicy.dispatchPlan(WatchMusicControlEvent.PREVIOUS, previousAndSeek, 1),
        )
    }

    private fun track(state: WatchPlaybackState) = WatchNowPlaying(
        title = "Track",
        artist = "Artist",
        state = state,
        positionSeconds = 10,
        durationSeconds = 180,
    )
}
