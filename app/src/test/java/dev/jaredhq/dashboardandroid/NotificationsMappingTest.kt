package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.dto.NotificationsPayloadDto
import dev.jaredhq.dashboardandroid.data.api.dto.toDomain
import dev.jaredhq.dashboardandroid.domain.model.NotificationKind
import dev.jaredhq.dashboardandroid.domain.model.NotificationPriority
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes a sample of the dashboard server's real notifications-feed JSON
 * (src/lib/intelligence/notifications.ts) and asserts the DTO -> domain mapping.
 * The contract regression test for the Android notification bridge: a server
 * shape change is caught here without a device.
 */
class NotificationsMappingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    // Note the reserved-word JSON field "when" and a future field the client
    // doesn't model ("extraFutureField"), to prove forward-compat.
    private val sample = """
        {
          "version": 1,
          "date": "2026-06-17",
          "generatedAt": "2026-06-17T07:30:00.000Z",
          "headline": "Deep work on the proposal",
          "items": [
            { "id": "headline", "kind": "headline", "title": "Deep work on the proposal",
              "detail": "Due Thursday", "timeLabel": null, "when": null, "href": "/tasks", "priority": "normal" },
            { "id": "event:event:8", "kind": "event", "title": "Standup", "detail": null,
              "timeLabel": "9:30 AM", "when": 1781679000, "href": "/calendar", "priority": "normal" },
            { "id": "deadline:task:12", "kind": "deadline", "title": "Submit proposal", "detail": null,
              "timeLabel": "Due today", "when": 1781654400, "href": "/tasks", "priority": "high" },
            { "id": "habit", "kind": "habit", "title": "2 habits left today", "detail": "Read, Walk",
              "timeLabel": null, "when": null, "href": "/habits", "priority": "low" }
          ],
          "counts": { "events": 1, "deadlines": 1, "habitsRemaining": 2 },
          "extraFutureField": "ignored"
        }
    """.trimIndent()

    @Test
    fun decodesAndMapsContract() {
        val payload = json.decodeFromString(NotificationsPayloadDto.serializer(), sample).toDomain()

        assertEquals(1, payload.version)
        assertEquals("2026-06-17", payload.date)
        assertEquals(4, payload.items.size)

        val event = payload.items.first { it.kind == NotificationKind.EVENT }
        assertEquals("Standup", event.title)
        assertEquals("9:30 AM", event.timeLabel)
        assertEquals(1781679000L, event.whenEpoch)

        val deadline = payload.items.first { it.kind == NotificationKind.DEADLINE }
        assertEquals(NotificationPriority.HIGH, deadline.priority)

        assertEquals(1, payload.counts.events)
        assertEquals(2, payload.counts.habitsRemaining)
    }

    @Test
    fun toleratesUnknownKindAndPriorityAndMissingFields() {
        val partial = """
            {
              "version": 1, "date": "2026-06-17", "generatedAt": "x", "headline": "Quiet day",
              "items": [
                { "id": "x", "kind": "spaceship", "title": "Mystery", "priority": "ultra" }
              ],
              "counts": { "events": 0, "deadlines": 0, "habitsRemaining": 0 }
            }
        """.trimIndent()

        val payload = json.decodeFromString(NotificationsPayloadDto.serializer(), partial).toDomain()
        val item = payload.items.single()
        assertEquals(NotificationKind.UNKNOWN, item.kind)
        assertEquals(NotificationPriority.UNKNOWN, item.priority)
        assertNull(item.timeLabel)
        assertNull(item.whenEpoch)
    }

    @Test
    fun toleratesEmptyFeed() {
        val empty = """
            { "version": 1, "date": "2026-06-17", "generatedAt": "x", "headline": "",
              "items": [], "counts": { "events": 0, "deadlines": 0, "habitsRemaining": 0 } }
        """.trimIndent()
        val payload = json.decodeFromString(NotificationsPayloadDto.serializer(), empty).toDomain()
        assertTrue(payload.items.isEmpty())
        assertEquals(0, payload.counts.events)
    }
}
