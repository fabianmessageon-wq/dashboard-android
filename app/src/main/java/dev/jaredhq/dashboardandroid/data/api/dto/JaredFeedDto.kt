package dev.jaredhq.dashboardandroid.data.api.dto

import dev.jaredhq.dashboardandroid.domain.model.DailyIntelligenceSettings
import dev.jaredhq.dashboardandroid.domain.model.FeedItemStatus
import dev.jaredhq.dashboardandroid.domain.model.FeedItemType
import dev.jaredhq.dashboardandroid.domain.model.JaredFeed
import dev.jaredhq.dashboardandroid.domain.model.JaredFeedItem
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Daily Intelligence ("Jared") surface:
 *   GET /api/daily-intelligence/feed     -> [JaredFeedPayloadDto]
 *   GET /api/daily-intelligence/settings -> [DailyIntelligenceSettingsDto]
 *
 * Defensively defaulted like the widget DTOs so a server-side contract change
 * degrades (unknown type/status maps to a safe fallback) rather than crashing
 * the worker. Only the fields the Android bridge actually uses are modelled;
 * `Json { ignoreUnknownKeys = true }` drops the rest (payload, timestamps, …).
 */
@Serializable
data class JaredFeedPayloadDto(
    val date: String = "",
    val items: List<JaredFeedItemDto> = emptyList(),
)

@Serializable
data class JaredFeedItemDto(
    val id: Long = 0,
    val date: String = "",
    val type: String = "unknown",
    val title: String = "",
    val body: String? = null,
    val status: String = "active",
    val relatedRunId: Long? = null,
)

@Serializable
data class DailyIntelligenceSettingsDto(
    val enabled: Boolean = true,
    val morningPlanningEnabled: Boolean = true,
    val middayMonitorEnabled: Boolean = true,
    val eveningReflectionEnabled: Boolean = false,
    val pushNotificationsEnabled: Boolean = true,
)

fun JaredFeedItemDto.toDomain(): JaredFeedItem = JaredFeedItem(
    id = id,
    date = date,
    type = FeedItemType.fromWire(type),
    title = title,
    body = body,
    status = FeedItemStatus.fromWire(status),
    relatedRunId = relatedRunId,
)

fun JaredFeedPayloadDto.toDomain(): JaredFeed = JaredFeed(
    date = date,
    items = items.map { it.toDomain() },
)

fun DailyIntelligenceSettingsDto.toDomain(): DailyIntelligenceSettings = DailyIntelligenceSettings(
    enabled = enabled,
    morningPlanningEnabled = morningPlanningEnabled,
    middayMonitorEnabled = middayMonitorEnabled,
    eveningReflectionEnabled = eveningReflectionEnabled,
    pushNotificationsEnabled = pushNotificationsEnabled,
)
