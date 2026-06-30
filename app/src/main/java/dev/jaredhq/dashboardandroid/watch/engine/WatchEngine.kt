package dev.jaredhq.dashboardandroid.watch.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Coarse connection lifecycle of a [WatchEngine], surfaced to the UI via
 * [WatchEngine.connectionState].
 *
 * Deliberately minimal and engine-agnostic: it carries no transport detail. The product
 * Watch screen renders connection + sync progress from these states; any richer per-engine detail
 * stays behind the boundary. [isConnected] is true for [CONNECTED] and [SYNCING] (and transiently
 * for [BINDING]); this flow adds the finer steps the UI needs.
 */
enum class WatchEngineConnectionState {
    /** No link and no scan in progress: the initial state, after [WatchEngine.disconnect], or after a failure. */
    DISCONNECTED,

    /** Scanning to find the target watch by MAC. */
    SCANNING,

    /** Target found; the BLE link is being established. */
    CONNECTING,

    /** Connected but not yet claimed by this app; `bind()` is in progress. */
    BINDING,

    /** Bind is waiting for the user to confirm the pairing on the watch face (`onNeedAuth`). */
    AWAITING_WATCH_CONFIRMATION,

    /** Connected and bound; idle and ready to sync. */
    CONNECTED,

    /** A health sync is in progress. */
    SYNCING,
}

/**
 * The watch integration boundary (ADR 0001).
 *
 * Everything above this interface speaks only the app's own domain types
 * ([WatchActivityDay], [WatchHeartRateDay], [WatchSleepSession], …). The active implementation
 * is [IdoSdkWatchEngine], which wraps the vendored IDO/VeryFit SDK — the ONLY place
 * `com.ido.*` / `com.veryfit.*` may be imported.
 *
 * Keeping the SDK behind this seam means another engine (e.g. a future clean-room one) can be
 * swapped in without touching callers.
 */
interface WatchEngine {

    /** Listener for decoded health data + sync lifecycle. Set before calling [syncHealth]. */
    var listener: WatchHealthListener?

    /**
     * Control events the **watch** initiates for the phone to act on (W7) — e.g. answering or
     * rejecting an incoming call from the wrist. Hot stream; an app component (the connection
     * service) collects it and performs the telephony action. Empty/never-emitting for engines
     * without device-control support.
     */
    val controlEvents: kotlinx.coroutines.flow.SharedFlow<WatchControlEvent>

    /** Phone-playback actions delivered by the watch over the engine transport. */
    val musicControlEvents: kotlinx.coroutines.flow.SharedFlow<WatchMusicControlEvent>

    /** Music feature flags for the connected watch; unknown until its function table arrives. */
    val musicCapabilities: StateFlow<WatchMusicCapabilities>

    /** On-watch MP3 library and storage. */
    val watchMusicLibrary: StateFlow<WatchMusicLibraryState>

    /** Current onboard-song reservation/transfer/cleanup lifecycle. */
    val watchMusicTransfer: StateFlow<WatchMusicTransferState>

    val watchMusicLibraryMutation: StateFlow<WatchMusicLibraryMutationState>

    /**
     * Current connection lifecycle, for the UI to observe. Starts at
     * [WatchEngineConnectionState.DISCONNECTED] and advances through scan → connect → bind → sync.
     * Hot and conflated (a [StateFlow]); safe to collect from Compose.
     */
    val connectionState: StateFlow<WatchEngineConnectionState>

    /**
     * One-time SDK/engine initialisation. Idempotent — safe to call from
     * `Application.onCreate`. Must run before any other method.
     */
    fun init()

    /** Begin connecting to the watch by MAC address (e.g. the known Active 4 Pro MAC). */
    fun connect(macAddress: String)

    /** Drop the current connection. */
    fun disconnect()

    /** Whether a watch is currently connected and ready. */
    fun isConnected(): Boolean

    /**
     * Trigger a health-data sync. Decoded results arrive on [listener]; this returns
     * immediately. No-op (or [WatchHealthListener.onSyncFailed]) if not connected.
     */
    fun syncHealth()

    /** Enable/disable phone music control on the watch. The desired state survives reconnects. */
    fun setPhoneMusicEnabled(enabled: Boolean): Boolean = false

    /** Push the phone's current media-session snapshot to the watch. */
    fun pushNowPlaying(nowPlaying: WatchNowPlaying): Boolean = false

    fun refreshWatchMusicLibrary(): Boolean = false

    fun importWatchSong(song: WatchSongImport): Boolean = false

    fun cancelWatchSongImport() {}

    fun deleteWatchSong(musicId: Int): Boolean = false

    fun createWatchMusicFolder(name: String): Boolean = false

    fun updateWatchMusicFolder(folderId: Int, name: String, musicIds: List<Int>): Boolean = false

    fun deleteWatchMusicFolder(folderId: Int): Boolean = false

    /** App-lifetime engines normally live until process death; tests/alternate hosts may release them. */
    fun release() {}

    /**
     * Push a single [notification] to the watch face (W7) — e.g. a dashboard reminder or quote.
     * Fire-and-forget; the watch shows it if the message feature is enabled there. Returns whether
     * the message was dispatched to the SDK (false if not connected or the engine has no notification
     * support). Default: a no-op returning false, so non-SDK engines compile unchanged.
     */
    fun sendNotification(notification: WatchNotification): Boolean = false

    /**
     * Tell the watch the incoming call is over (answered elsewhere, ended, or the call notification
     * was dismissed) so it stops showing the incoming-call screen (W7). Pairs with a [CALL][
     * WatchNotificationCategory.CALL] [sendNotification], which routes through the watch's dedicated
     * call API. Default: a no-op, so non-SDK engines compile unchanged.
     */
    fun stopIncomingCall() {}
}

/**
 * A message to surface on the watch face (W7). [appName] is shown as the source/sender label and
 * [body] as the message text. [category] lets the engine pick the right on-watch message type (so an
 * SMS or a call render as such, not a generic alert). Engine-agnostic and free of any `com.ido.*`
 * reference; the engine maps it onto whatever message API the connected watch supports.
 */
data class WatchNotification(
    val appName: String,
    val body: String,
    val category: WatchNotificationCategory = WatchNotificationCategory.GENERIC,
)

/**
 * Kind of message being mirrored, so the engine can choose the matching on-watch type. Display-only:
 * an incoming [CALL] shows on the watch but answer/reject-from-watch (call control) is a later step.
 */
enum class WatchNotificationCategory {
    GENERIC,
    SMS,
    EMAIL,
    CALL,
    MISSED_CALL,
}

/** A control action the watch asks the phone to perform (W7 call control). */
enum class WatchControlEvent {
    ANSWER_CALL,
    REJECT_CALL,
    MUTE_CALL,
}
