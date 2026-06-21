package dev.jaredhq.dashboardandroid.data.api.dto

import dev.jaredhq.dashboardandroid.domain.model.WatchSyncEvent
import dev.jaredhq.dashboardandroid.domain.model.WatchSyncRequest
import dev.jaredhq.dashboardandroid.domain.model.WatchSyncResult
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for `POST /api/widget/v1/watch/sync` (Phase 2 — safe dashboard
 * metrics). Mirrors the request body documented in `docs/plans/ble-master-plan.md`:
 * device + connection telemetry plus developer-only raw handshake events. No
 * health metrics are sent in this phase.
 */
@Serializable
data class WatchSyncDto(
    val deviceId: String,
    val deviceName: String? = null,
    val connectedAt: String? = null,
    val disconnectedAt: String? = null,
    val batteryPercent: Int? = null,
    val mtu: Int? = null,
    val connectionState: String,
    val protocolVersion: String? = null,
    val rawEvents: List<WatchRawEventDto> = emptyList(),
)

@Serializable
data class WatchRawEventDto(
    val direction: String,
    val characteristic: String? = null,
    val commandFamily: String? = null,
    val hex: String,
    val timestamp: String,
)

/**
 * Server acknowledgement. Defensively defaulted like the other widget DTOs so a
 * contract skew (or an empty 2xx body) degrades to "accepted" rather than
 * crashing the sync worker.
 */
@Serializable
data class WatchSyncResponseDto(
    val accepted: Boolean = true,
    val deviceId: String? = null,
    val recordedAt: String? = null,
)

fun WatchSyncRequest.toDto(): WatchSyncDto = WatchSyncDto(
    deviceId = deviceId,
    deviceName = deviceName,
    connectedAt = connectedAt,
    disconnectedAt = disconnectedAt,
    batteryPercent = batteryPercent,
    mtu = mtu,
    connectionState = connectionState,
    protocolVersion = protocolVersion,
    rawEvents = rawEvents.map { it.toDto() },
)

fun WatchSyncEvent.toDto(): WatchRawEventDto = WatchRawEventDto(
    direction = direction,
    characteristic = characteristic,
    commandFamily = commandFamily,
    hex = hex,
    timestamp = timestamp,
)

fun WatchSyncResponseDto.toDomain(): WatchSyncResult = WatchSyncResult(
    accepted = accepted,
    deviceId = deviceId,
    recordedAt = recordedAt,
)
