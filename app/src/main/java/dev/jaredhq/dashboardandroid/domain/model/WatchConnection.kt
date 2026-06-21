package dev.jaredhq.dashboardandroid.domain.model

/**
 * Domain model representing the current state of a BLE watch connection.
 * A plain Kotlin type with no Android dependencies, suitable for UI and sync.
 *
 * @property deviceAddress The BLE MAC address of the connected watch.
 * @property deviceName The advertised Bluetooth device name, if available.
 * @property batteryPercent Battery level (0-100), or null if not yet read.
 * @property mtu Negotiated MTU size (typically 247 after requesting 517).
 * @property macAddress The watch's own MAC address from command 301 response, or null.
 * @property isConnected Whether the watch is currently connected.
 * @property lastSyncedAt Epoch millis of the last successful sync, or null.
 */
data class WatchConnection(
    val deviceAddress: String = "",
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val mtu: Int = 23,
    val macAddress: String? = null,
    val isConnected: Boolean = false,
    val lastSyncedAt: Long? = null,
)
