package dev.jaredhq.dashboardandroid.domain.model

/**
 * Phase 2 "safe dashboard metrics" upload payload — connection/device telemetry
 * only, no health data. Built from the live BLE state and posted to
 * `POST /api/widget/v1/watch/sync`.
 *
 * A plain Kotlin type with no Android/Retrofit dependencies, so it crosses the
 * [dev.jaredhq.dashboardandroid.data.api.DashboardApiClient] boundary in domain
 * terms (the DTO mapping lives in the data layer).
 *
 * @property deviceId Stable device identity — the watch MAC (preferred) or the
 *   BLE address. Used by the dashboard as the `watch_devices` primary key.
 * @property deviceName Advertised Bluetooth name, if known.
 * @property connectedAt ISO-8601 UTC instant the current connection began, or null.
 * @property disconnectedAt ISO-8601 UTC instant of the last disconnect, or null.
 * @property batteryPercent Battery level (0-100), or null if not yet read.
 * @property mtu Negotiated MTU, or null if still at the default.
 * @property connectionState One of `connected | connecting | scanning |
 *   disconnected | error` — the lowercase wire form of the BLE state.
 * @property protocolVersion VeryFit protocol version, if ever decoded (null in Phase 2).
 * @property rawEvents Developer-only handshake packets captured this session.
 */
data class WatchSyncRequest(
    val deviceId: String,
    val deviceName: String? = null,
    val connectedAt: String? = null,
    val disconnectedAt: String? = null,
    val batteryPercent: Int? = null,
    val mtu: Int? = null,
    val connectionState: String,
    val protocolVersion: String? = null,
    val rawEvents: List<WatchSyncEvent> = emptyList(),
)

/**
 * One raw BLE packet, in the dashboard's `watch_raw_events` shape. Developer-only
 * and short-lived (the dashboard expires these after 24h); no health metrics are
 * decoded from them in Phase 2.
 *
 * @property direction `phone->watch` or `watch->phone`.
 * @property characteristic Short characteristic id (e.g. `0x0AF6`), or null.
 * @property commandFamily Leading command byte (e.g. `0x02`), or null.
 * @property hex Contiguous lowercase payload hex (e.g. `0204`).
 * @property timestamp ISO-8601 UTC instant the packet was logged.
 */
data class WatchSyncEvent(
    val direction: String,
    val characteristic: String? = null,
    val commandFamily: String? = null,
    val hex: String,
    val timestamp: String,
)

/**
 * The dashboard's acknowledgement of a [WatchSyncRequest].
 *
 * @property accepted Whether the server stored the telemetry.
 * @property deviceId The device id the server recorded against, if echoed back.
 * @property recordedAt ISO-8601 UTC instant the server recorded the sync, if any.
 */
data class WatchSyncResult(
    val accepted: Boolean,
    val deviceId: String? = null,
    val recordedAt: String? = null,
)
