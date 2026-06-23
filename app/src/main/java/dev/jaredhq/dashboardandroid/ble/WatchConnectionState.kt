package dev.jaredhq.dashboardandroid.ble

/**
 * Sealed class representing the BLE connection lifecycle state for the Watch tab.
 */
sealed class WatchConnectionState {
    /** Idle / disconnected. */
    data object Disconnected : WatchConnectionState()

    /** Actively scanning for BLE devices advertising the VeryFit service. */
    data object Scanning : WatchConnectionState()

    /** Connecting to a specific device (or generic connecting state). */
    data class Connecting(val deviceAddress: String) : WatchConnectionState()

    /**
     * GATT link is up and the VeryFit service is discovered. This is **not** the same
     * as "ready to write": notification/CCCD setup runs after this state is emitted.
     * Writes must be gated on [ready] so a command never races the descriptor-write
     * chain (Android GATT has no internal op queue; overlapping ops silently fail).
     *
     * @property deviceAddress The BLE MAC address of the connected watch.
     * @property deviceName The advertised device name, if available.
     * @property ready True once notification enablement has finished and the watch is
     *   safe to write to. False during the connected-but-initialising window.
     * @property batteryInfo Detailed battery info from command 321, or null if not yet received.
     * @property batteryPercent Battery level (0-100) derived from [batteryInfo], or null.
     * @property mtu Negotiated MTU size (typically 247 after requesting 517).
     * @property macAddress The watch's own MAC address from command 301 response, or null.
     * @property lastCommandHex The last command sent, as a hex string for display.
     * @property lastResponseHex The last raw response received, as a hex string.
     */
    data class Connected(
        val deviceAddress: String,
        val deviceName: String? = null,
        val ready: Boolean = false,
        val batteryInfo: WatchBatteryInfo? = null,
        val batteryPercent: Int? = batteryInfo?.level,
        val mtu: Int = 23,
        val macAddress: String? = null,
        val lastCommandHex: String? = null,
        val lastResponseHex: String? = null,
    ) : WatchConnectionState()

    /** Terminal error state (scan failed, connection lost, service missing, etc.). */
    data class Error(val reason: String) : WatchConnectionState()
}
