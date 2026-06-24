package dev.jaredhq.dashboardandroid.watch.engine

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
}
