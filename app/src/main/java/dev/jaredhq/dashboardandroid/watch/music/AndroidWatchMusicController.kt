package dev.jaredhq.dashboardandroid.watch.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.WatchMusicControlEvent
import dev.jaredhq.dashboardandroid.watch.engine.WatchNowPlaying
import dev.jaredhq.dashboardandroid.watch.engine.WatchPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Observes Android media sessions using the app's existing notification-listener grant, pushes
 * privacy-safe domain snapshots through [WatchEngine], and applies watch controls to the selected
 * session. Track metadata is never logged.
 */
class AndroidWatchMusicController(
    context: Context,
    private val engine: WatchEngine,
    private val scope: CoroutineScope,
) : WatchMusicController {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(
        PhoneMusicState(enabled = preferences.getBoolean(KEY_ENABLED, false)),
    )
    override val state: StateFlow<PhoneMusicState> = _state.asStateFlow()

    private var sessionManager: MediaSessionManager? = null
    private var notificationListener: ComponentName? = null
    private var selectedController: MediaController? = null
    private var musicControlJob: Job? = null
    private var lastEngineSnapshot: WatchNowPlaying? = null
    private var lastEnginePushElapsedMs: Long = 0L

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener {
        selectController(it.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishSelected()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishSelected()
        override fun onSessionDestroyed() = refreshSessions()
    }

    init {
        musicControlJob = scope.launch {
            engine.musicControlEvents.collect { event -> handleMusicControl(event) }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.update { it.copy(enabled = enabled) }
        if (!enabled) {
            lastEngineSnapshot = null
            lastEnginePushElapsedMs = 0L
        }
        applyEffectiveEnabled()
        if (enabled && _state.value.notificationListenerConnected) refreshSessions()
    }

    override fun start(notificationListener: ComponentName) {
        if (this.notificationListener == notificationListener &&
            _state.value.notificationListenerConnected
        ) {
            refreshSessions()
            return
        }
        stopSessionObservation(clearTrack = false)
        this.notificationListener = notificationListener
        val manager = appContext.getSystemService(MediaSessionManager::class.java)
        sessionManager = manager
        runCatching {
            manager?.addOnActiveSessionsChangedListener(sessionsChangedListener, notificationListener)
            _state.update { it.copy(notificationListenerConnected = true) }
            Log.i(TAG, "media-session observation started")
            applyEffectiveEnabled()
            refreshSessions()
        }.onFailure {
            _state.update { it.copy(notificationListenerConnected = false) }
            Log.w(TAG, "media-session observation failed", it)
            engine.setPhoneMusicEnabled(false)
        }
    }

    override fun stop() {
        if (!_state.value.notificationListenerConnected && notificationListener == null) return
        stopSessionObservation(clearTrack = true)
        notificationListener = null
        _state.update { it.copy(notificationListenerConnected = false) }
        engine.setPhoneMusicEnabled(false)
        Log.i(TAG, "media-session observation stopped")
    }

    private fun stopSessionObservation(clearTrack: Boolean) {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        selectedController?.unregisterCallback(controllerCallback)
        selectedController = null
        sessionManager = null
        if (clearTrack) _state.update { it.copy(nowPlaying = null) }
    }

    private fun applyEffectiveEnabled() {
        val current = _state.value
        engine.setPhoneMusicEnabled(current.enabled && current.notificationListenerConnected)
        if (current.enabled && current.notificationListenerConnected) {
            current.nowPlaying?.let(::pushSnapshot)
        }
    }

    private fun refreshSessions() {
        val manager = sessionManager ?: return
        val listener = notificationListener ?: return
        runCatching { manager.getActiveSessions(listener) }
            .onSuccess(::selectController)
            .onFailure { Log.w(TAG, "active media-session query failed", it) }
    }

    private fun selectController(controllers: List<MediaController>) {
        val selected = controllers.maxByOrNull { controller ->
            WatchMusicPolicy.playbackPriority(
                controller.playbackState.toWatchPlaybackState(WatchPlaybackState.PLAYING),
            )
        }
        if (selectedController?.sessionToken != selected?.sessionToken) {
            selectedController?.unregisterCallback(controllerCallback)
            selectedController = selected
            selected?.registerCallback(controllerCallback, handler)
            Log.i(TAG, "active media session changed (present=${selected != null})")
        }
        publishSelected()
    }

    private fun publishSelected() {
        val controller = selectedController
        val snapshot = controller?.toWatchNowPlaying()
        if (_state.value.nowPlaying == snapshot) return
        _state.update { it.copy(nowPlaying = snapshot) }
        Log.i(
            TAG,
            "now-playing changed state=${snapshot?.state} " +
                "titleLen=${snapshot?.title?.length ?: 0} artistLen=${snapshot?.artist?.length ?: 0}",
        )
        val current = _state.value
        if (current.enabled && current.notificationListenerConnected && snapshot != null) {
            pushSnapshot(snapshot)
        }
    }

    private fun pushSnapshot(snapshot: WatchNowPlaying) {
        val now = SystemClock.elapsedRealtime()
        if (!WatchMusicPolicy.shouldPushToWatch(
                previous = lastEngineSnapshot,
                next = snapshot,
                elapsedSincePushMs = now - lastEnginePushElapsedMs,
            )
        ) {
            return
        }
        if (engine.pushNowPlaying(snapshot)) {
            lastEngineSnapshot = snapshot
            lastEnginePushElapsedMs = now
        }
    }

    private suspend fun handleMusicControl(event: WatchMusicControlEvent) {
        val current = _state.value
        if (!current.enabled || !current.notificationListenerConnected) return

        // The watch also advertises AVRCP. Give Android a short window to apply a native action;
        // dispatch the IDO/GATT event only when no corresponding media-session change appeared.
        val before = current.nowPlaying
        delay(AVRCP_GRACE_MS)
        val after = _state.value.nowPlaying
        if (WatchMusicPolicy.nativeEffectObserved(event, before, after)) {
            Log.i(TAG, "music control $event handled natively")
            return
        }

        val dispatched = dispatchToSelectedController(event) || dispatchMediaKey(event)
        Log.i(TAG, "music control $event GATT fallback dispatched=$dispatched")
    }

    private suspend fun dispatchToSelectedController(event: WatchMusicControlEvent): Boolean {
        val controller = selectedController ?: return false
        val playback = controller.playbackState
        val plan = WatchMusicPolicy.dispatchPlan(
            event = event,
            actions = playback?.actions ?: 0L,
            positionSeconds = WatchMusicPolicy.millisecondsToSeconds(playback?.position ?: 0L),
        )
        if (plan == WatchMusicPolicy.DispatchPlan.MEDIA_KEY) {
            Log.i(TAG, "music control $event transport unsupported; using media key")
            return false
        }
        val controls = controller.transportControls
        return runCatching {
            if (plan == WatchMusicPolicy.DispatchPlan.SEEK_THEN_PREVIOUS) {
                controls.seekTo(0L)
                delay(PREVIOUS_SEEK_SETTLE_MS)
                controls.skipToPrevious()
            } else {
                when (event) {
                    WatchMusicControlEvent.PLAY -> controls.play()
                    WatchMusicControlEvent.PAUSE -> controls.pause()
                    WatchMusicControlEvent.STOP -> controls.stop()
                    WatchMusicControlEvent.NEXT -> controls.skipToNext()
                    WatchMusicControlEvent.PREVIOUS -> controls.skipToPrevious()
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "media-session control failed action=$event", it)
            false
        }
    }

    private fun dispatchMediaKey(event: WatchMusicControlEvent): Boolean {
        val keyCode = when (event) {
            WatchMusicControlEvent.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            WatchMusicControlEvent.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            WatchMusicControlEvent.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
            WatchMusicControlEvent.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            WatchMusicControlEvent.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            val now = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        }.getOrElse {
            Log.w(TAG, "media-key fallback failed action=$event", it)
            false
        }
    }

    private fun MediaController.toWatchNowPlaying(): WatchNowPlaying? {
        val metadata = metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val playback = playbackState
        return WatchNowPlaying(
            title = title,
            artist = artist,
            state = playback.toWatchPlaybackState(_state.value.nowPlaying?.state)
                ?: WatchPlaybackState.STOPPED,
            positionSeconds = WatchMusicPolicy.millisecondsToSeconds(playback?.position ?: 0L),
            durationSeconds = WatchMusicPolicy.millisecondsToSeconds(
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            ),
        )
    }

    private fun PlaybackState?.toWatchPlaybackState(
        skippingFallback: WatchPlaybackState? = null,
    ): WatchPlaybackState? = when (this?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_CONNECTING,
        -> WatchPlaybackState.PLAYING
        PlaybackState.STATE_PAUSED -> WatchPlaybackState.PAUSED
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_NONE,
        PlaybackState.STATE_ERROR,
        -> WatchPlaybackState.STOPPED
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> skippingFallback ?: WatchPlaybackState.PLAYING
        else -> null
    }

    private companion object {
        const val TAG = "WatchMusic"
        const val PREFERENCES = "watch_music_settings"
        const val KEY_ENABLED = "phone_music_enabled"
        const val AVRCP_GRACE_MS = 350L
        const val PREVIOUS_SEEK_SETTLE_MS = 100L
    }
}
