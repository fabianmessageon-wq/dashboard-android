package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.dto.CaptureResponseDto
import dev.jaredhq.dashboardandroid.data.api.dto.FocusStartResponseDto
import dev.jaredhq.dashboardandroid.data.api.dto.TodayPayloadDto
import dev.jaredhq.dashboardandroid.data.api.dto.toDirectCaptureResult
import dev.jaredhq.dashboardandroid.data.api.dto.toDomain
import dev.jaredhq.dashboardandroid.data.api.dto.toDto
import dev.jaredhq.dashboardandroid.domain.model.ActionState
import dev.jaredhq.dashboardandroid.domain.model.CaptureMode
import dev.jaredhq.dashboardandroid.domain.model.ReadinessBand
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes a sample of the dashboard server's real `WidgetTodayPayload` JSON and
 * asserts the DTO -> domain mapping. This is the contract regression test: if
 * the server contract changes, this catches it without a device.
 */
class PayloadMappingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    // Mirrors src/lib/intelligence/widgetPayload.ts output, including a server
    // field the client doesn't model ("extraFutureField") to prove forward-compat.
    private val sample = """
        {
          "version": 1,
          "date": "2026-06-17",
          "generatedAt": "2026-06-17T07:30:00.000Z",
          "headline": "Deep work on the proposal",
          "recoveryMode": false,
          "readiness": { "score": 72, "band": "high" },
          "mainAction": { "title": "Proposal", "detail": "Due Thursday", "href": "/tasks", "taskId": 12 },
          "focusBlock": { "startLabel": "09:30", "endLabel": "11:00", "taskId": 12 },
          "bodyAction": { "title": "Move your body", "detail": null, "href": "/workouts", "state": "do" },
          "resetAction": { "title": "Reflect", "detail": "Two minutes", "href": "/reflection", "state": "do" },
          "habits": [
            { "id": 1, "title": "Read", "doneToday": false },
            { "id": 2, "title": "Meditate", "doneToday": true },
            { "id": 3, "title": "Walk", "doneToday": false }
          ],
          "habitsRemaining": 2,
          "warnings": ["3 tasks due today"],
          "extraFutureField": "ignored"
        }
    """.trimIndent()

    @Test
    fun decodesAndMapsContract() {
        val dto = json.decodeFromString(TodayPayloadDto.serializer(), sample)
        val today = dto.toDomain()

        assertEquals(1, today.version)
        assertEquals("2026-06-17", today.date)
        assertEquals(72, today.readiness.score)
        assertEquals(ReadinessBand.HIGH, today.readiness.band)
        assertEquals(ActionState.DO, today.bodyAction.state)

        // Focus labels are tz-local strings straight off the wire (no epoch).
        assertEquals("09:30", today.focusBlock?.startLabel)
        assertEquals("11:00", today.focusBlock?.endLabel)
        assertEquals(12, today.focusBlock?.taskId)

        // habitsRemaining honored.
        assertEquals(2, today.habitsRemaining)
        assertEquals(3, today.habits.size)
        assertEquals("Read", today.habits.first().title)
    }

    @Test
    fun toleratesNullMainActionAndUnknownEnum() {
        val partial = """
            {
              "version": 1, "date": "2026-06-17", "generatedAt": "x",
              "headline": "Nothing urgent", "recoveryMode": true,
              "readiness": { "score": 30, "band": "weird" },
              "mainAction": null, "focusBlock": null,
              "bodyAction": { "title": "Rest", "detail": null, "href": "/x", "state": "rest" },
              "resetAction": { "title": "Rest up", "detail": null, "href": "/x", "state": "??" },
              "habits": [], "habitsRemaining": 0, "warnings": []
            }
        """.trimIndent()

        val today = json.decodeFromString(TodayPayloadDto.serializer(), partial).toDomain()
        assertNull(today.mainAction)
        assertNull(today.focusBlock)
        assertTrue(today.recoveryMode)
        // Unknown band/state degrade instead of throwing.
        assertEquals(ReadinessBand.UNKNOWN, today.readiness.band)
        assertEquals(ActionState.UNKNOWN, today.resetAction.state)
    }

    @Test
    fun roundTripsThroughDto() {
        val today = json.decodeFromString(TodayPayloadDto.serializer(), sample).toDomain()
        val reEncoded = json.encodeToString(TodayPayloadDto.serializer(), today.toDto())
        val again = json.decodeFromString(TodayPayloadDto.serializer(), reEncoded).toDomain()
        assertEquals(today, again)
    }

    @Test
    fun decodesFocusStartResponseWithSession() {
        // Mirrors /focus/start: { ...todayPayload, session: { id, fireAt } }.
        val body = """
            {
              "version": 1, "date": "2026-06-17", "generatedAt": "x",
              "headline": "Focus", "recoveryMode": false,
              "readiness": { "score": 60, "band": "moderate" },
              "mainAction": null,
              "focusBlock": { "startLabel": "09:30", "endLabel": "11:00", "taskId": 12 },
              "bodyAction": { "title": "Move", "detail": null, "href": "/x", "state": "do" },
              "resetAction": { "title": "Reflect", "detail": null, "href": "/x", "state": "do" },
              "habits": [], "habitsRemaining": 0, "warnings": [],
              "session": { "id": 42, "fireAt": 1750000000 }
            }
        """.trimIndent()

        val result = json.decodeFromString(FocusStartResponseDto.serializer(), body).toDomain()
        assertEquals(42L, result.session?.id)
        assertEquals(1750000000L, result.session?.fireAt)
        assertEquals("09:30", result.today.focusBlock?.startLabel)
    }

    @Test
    fun decodesDirectCaptureResponseAsDirectModeWithTaskId() {
        // Mirrors /capture: { ...todayPayload, createdTaskId } — no captureMode.
        val body = """
            {
              "version": 1, "date": "2026-06-17", "generatedAt": "x",
              "headline": "Today", "recoveryMode": false,
              "readiness": { "score": 50, "band": "moderate" },
              "mainAction": null, "focusBlock": null,
              "bodyAction": { "title": "Move", "detail": null, "href": "/x", "state": "do" },
              "resetAction": { "title": "Reflect", "detail": null, "href": "/x", "state": "do" },
              "habits": [], "habitsRemaining": 0, "warnings": [],
              "createdTaskId": 87
            }
        """.trimIndent()

        val result = json.decodeFromString(CaptureResponseDto.serializer(), body).toDirectCaptureResult()
        assertEquals(CaptureMode.DIRECT, result.mode)
        assertEquals(87, result.createdTaskId)
        assertNull(result.reply)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun decodesChatFallbackWithoutPendingConfirmation() {
        // The /chat task-fallback path omits pendingConfirmation — must default empty.
        val body = """
            {
              "version": 1, "date": "2026-06-17", "generatedAt": "x",
              "headline": "Today", "recoveryMode": false,
              "readiness": { "score": 50, "band": "moderate" },
              "mainAction": null, "focusBlock": null,
              "bodyAction": { "title": "Move", "detail": null, "href": "/x", "state": "do" },
              "resetAction": { "title": "Reflect", "detail": null, "href": "/x", "state": "do" },
              "habits": [], "habitsRemaining": 0, "warnings": [],
              "reply": "AI is off — saved it as a task.",
              "actions": ["create_task"],
              "createdTaskId": 5,
              "captureMode": "task-fallback"
            }
        """.trimIndent()

        val result = json.decodeFromString(CaptureResponseDto.serializer(), body).toDomain()
        assertEquals(CaptureMode.TASK_FALLBACK, result.mode)
        assertTrue(result.pendingConfirmation.isEmpty())
        assertEquals(listOf("create_task"), result.actions)
        assertEquals(5, result.createdTaskId)
    }

    @Test
    fun recomputesHabitsRemainingWhenServerSendsZeroButHabitsRemain() {
        // Defensive mapper branch: server says 0 remaining but two are undone.
        val dto = TodayPayloadDto(
            habits = listOf(
                dev.jaredhq.dashboardandroid.data.api.dto.HabitDto(1, "A", doneToday = false),
                dev.jaredhq.dashboardandroid.data.api.dto.HabitDto(2, "B", doneToday = false),
            ),
            habitsRemaining = 0,
        )
        assertEquals(2, dto.toDomain().habitsRemaining)
    }
}
