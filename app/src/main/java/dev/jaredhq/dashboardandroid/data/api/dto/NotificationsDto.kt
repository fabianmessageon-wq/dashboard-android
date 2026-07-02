package dev.jaredhq.dashboardandroid.data.api.dto

import dev.jaredhq.dashboardandroid.domain.model.NotificationCounts
import dev.jaredhq.dashboardandroid.domain.model.NotificationItem
import dev.jaredhq.dashboardandroid.domain.model.NotificationKind
import dev.jaredhq.dashboardandroid.domain.model.NotificationPriority
import dev.jaredhq.dashboardandroid.domain.model.NotificationsPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for GET /api/widget/v1/notifications. Defensively nullable like the
 * other widget DTOs so a contract skew degrades rather than crashes the worker.
 */
@Serializable
data class NotificationsPayloadDto(
    val version: Int = 0,
    val date: String = "",
    val generatedAt: String = "",
    val headline: String = "",
    val items: List<NotificationItemDto> = emptyList(),
    val counts: NotificationCountsDto = NotificationCountsDto(),
)

@Serializable
data class NotificationItemDto(
    val id: String = "",
    val kind: String = "unknown",
    val title: String = "",
    val detail: String? = null,
    val timeLabel: String? = null,
    // "when" is a Kotlin keyword; map the JSON field explicitly.
    @SerialName("when") val whenEpoch: Long? = null,
    val href: String = "",
    val priority: String = "normal",
)

@Serializable
data class NotificationCountsDto(
    val events: Int = 0,
    val deadlines: Int = 0,
    val habitsRemaining: Int = 0,
    // Payload v2; defaults keep a v1 server parseable.
    val reminders: Int = 0,
)

fun NotificationItemDto.toDomain(): NotificationItem = NotificationItem(
    id = id,
    kind = NotificationKind.fromWire(kind),
    title = title,
    detail = detail,
    timeLabel = timeLabel,
    whenEpoch = whenEpoch,
    href = href,
    priority = NotificationPriority.fromWire(priority),
)

fun NotificationCountsDto.toDomain(): NotificationCounts =
    NotificationCounts(
        events = events,
        deadlines = deadlines,
        habitsRemaining = habitsRemaining,
        reminders = reminders,
    )

fun NotificationsPayloadDto.toDomain(): NotificationsPayload = NotificationsPayload(
    version = version,
    date = date,
    generatedAt = generatedAt,
    headline = headline,
    items = items.map { it.toDomain() },
    counts = counts.toDomain(),
)
