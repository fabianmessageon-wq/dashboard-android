package dev.jaredhq.dashboardandroid.data.api.dto

import kotlinx.serialization.Serializable

/**
 * DTO for uploading watch connection status to the dashboard server.
 * Sent as a POST body to the watch sync endpoint.
 */
@Serializable
data class WatchSyncDto(
    val deviceAddress: String = "",
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val mtu: Int = 23,
    val macAddress: String? = null,
    val isConnected: Boolean = false,
    val lastSyncedAt: Long? = null,
)
