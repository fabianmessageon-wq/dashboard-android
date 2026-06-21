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
     * Fully connected: services discovered, notifications enabled, ready for commands.
     *
     * @property deviceAddress The BLE MAC address of the connected watch.
     * @property deviceName The advertised device name, if available.
     * @property batteryPercent Battery level (0-100), or null if not yet read.
     * @property mtu Negotiated MTU size (typically 247 after requesting 517).
     * @property macAddress The watch's own MAC address from 02:04 response, or null.
     * @property lastCommandHex The last command sent, as a hex string for display.
     * @property lastResponseHex The last raw response received, as a hex string.
     */
    data class Connected(
        val deviceAddress: String,
        val deviceName: String? = null,
        val batteryPercent: Int? = null,
        val mtu: Int = 23,
        val macAddress: String? = null,
        val lastCommandHex: String? = null,
        val lastResponseHex: String? = null,
    ) : WatchConnectionState()

    /** Terminal error state (scan failed, connection lost, service missing, etc.). */
    data class Error(val reason: String) : WatchConnectionState()
}
