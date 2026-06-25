package dev.jaredhq.dashboardandroid.watch.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Coarse connection lifecycle of a [WatchEngine], surfaced to the UI via
 * [WatchEngine.connectionState].
 *
 * Deliberately minimal and engine-agnostic: it carries no transport detail (the clean-room
 * `ble.WatchConnectionState` keeps GATT/MTU/raw-packet fields for the debug console). The product
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
 * ([WatchActivityDay], [WatchHeartRateDay], [WatchSleepSession], …). Two implementations
 * exist:
 *
 *  - [IdoSdkWatchEngine] — wraps the vendored IDO/VeryFit SDK (the active engine for the
 *    private build). The ONLY place `com.ido.*` / `com.veryfit.*` may be imported.
 *  - a future `CleanRoomWatchEngine` — the existing direct-BLE clean-room code
 *    (`WatchBleManager`/`WatchProtocol`), retained as the long-term independent path.
 *
 * Keeping the SDK behind this seam makes "clean-room later" a swap, not a rewrite.
 */
interface WatchEngine {

    /** Listener for decoded health data + sync lifecycle. Set before calling [syncHealth]. */
    var listener: WatchHealthListener?

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

    /**
     * Push a single [notification] to the watch face (W7) — e.g. a dashboard reminder or quote.
     * Fire-and-forget; the watch shows it if the message feature is enabled there. Returns whether
     * the message was dispatched to the SDK (false if not connected or the engine has no notification
     * support). Default: a no-op returning false, so non-SDK engines compile unchanged.
     */
    fun sendNotification(notification: WatchNotification): Boolean = false
}

/**
 * A message to surface on the watch face (W7). [appName] is shown as the source/sender label and
 * [body] as the message text. Engine-agnostic and free of any `com.ido.*` reference; the engine maps
 * it onto whatever message API the connected watch supports.
 */
data class WatchNotification(
    val appName: String,
    val body: String,
)
