package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.dto.DailyIntelligenceSettingsDto
import dev.jaredhq.dashboardandroid.data.api.dto.JaredFeedPayloadDto
import dev.jaredhq.dashboardandroid.data.api.dto.toDomain
import dev.jaredhq.dashboardandroid.domain.model.DailyIntelligenceSettings
import dev.jaredhq.dashboardandroid.domain.model.FeedItemStatus
import dev.jaredhq.dashboardandroid.domain.model.FeedItemType
import dev.jaredhq.dashboardandroid.domain.model.JaredCategory
import dev.jaredhq.dashboardandroid.domain.model.JaredFeedItem
import dev.jaredhq.dashboardandroid.notify.JaredNotifier
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract + pure-logic tests for the Daily Intelligence ("Jared") notification
 * bridge. Decodes the real server JSON shape (src/lib/daily-intelligence/feed.ts
 * `SerializedFeedItem` + settings route) and exercises the on-device gating /
 * formatting / deep-link helpers without a device.
 */
class JaredFeedMappingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    // Mirrors GET /api/daily-intelligence/feed: { date, items: SerializedFeedItem[] }.
    // Includes fields the client doesn't model (source, payload, timestamps) and a
    // future type to prove forward-compat.
    private val feedSample = """
        {
          "date": "2026-06-30",
          "items": [
            { "id": 42, "date": "2026-06-30", "source": "jared", "type": "morning_plan_ready",
              "title": "Your plan is ready", "body": "3 focus blocks scheduled.\nTap to review.",
              "status": "active", "relatedRunId": 7, "payload": {"x":1},
              "createdAt": "2026-06-30T07:00:00.000Z", "resolvedAt": null, "dismissedAt": null },
            { "id": 43, "date": "2026-06-30", "source": "jared", "type": "plan_running_late",
              "title": "Running late", "body": null, "status": "active", "relatedRunId": 7 },
            { "id": 40, "date": "2026-06-30", "source": "jared", "type": "plan_approved",
              "title": "Plan approved", "body": "Locked in.", "status": "resolved", "relatedRunId": 7 },
            { "id": 39, "date": "2026-06-30", "source": "jared", "type": "warp_drive_engaged",
              "title": "Mystery", "body": null, "status": "active", "relatedRunId": null }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesAndMapsFeedContract() {
        val feed = json.decodeFromString(JaredFeedPayloadDto.serializer(), feedSample).toDomain()
        assertEquals("2026-06-30", feed.date)
        assertEquals(4, feed.items.size)

        val morning = feed.items.first { it.id == 42L }
        assertEquals(FeedItemType.MORNING_PLAN_READY, morning.type)
        assertEquals(JaredCategory.MORNING, morning.category)
        assertEquals(FeedItemStatus.ACTIVE, morning.status)
        assertEquals(7L, morning.relatedRunId)

        // Unknown server type degrades to UNKNOWN/RESULT, not a crash or a drop.
        val unknown = feed.items.first { it.id == 39L }
        assertEquals(FeedItemType.UNKNOWN, unknown.type)
        assertEquals(JaredCategory.RESULT, unknown.category)
        assertNull(unknown.relatedRunId)
    }

    @Test
    fun settingsDefaultsToServerDefaults() {
        // An empty object must decode to the server's documented defaults.
        val settings = json.decodeFromString(DailyIntelligenceSettingsDto.serializer(), "{}").toDomain()
        assertTrue(settings.enabled)
        assertTrue(settings.morningPlanningEnabled)
        assertTrue(settings.middayMonitorEnabled)
        assertFalse(settings.eveningReflectionEnabled)
        assertTrue(settings.pushNotificationsEnabled)
    }

    @Test
    fun masterPushSwitchSuppressesEveryCategory() {
        val off = DailyIntelligenceSettings(pushNotificationsEnabled = false)
        JaredCategory.entries.forEach { assertFalse(off.allows(it)) }
    }

    @Test
    fun perCategoryTogglesGateTheRightCategory() {
        val noMidday = DailyIntelligenceSettings(middayMonitorEnabled = false)
        assertTrue(noMidday.allows(JaredCategory.MORNING))
        assertFalse(noMidday.allows(JaredCategory.MIDDAY))

        // Evening is off by default; preference is never a heads-up.
        val defaults = DailyIntelligenceSettings()
        assertFalse(defaults.allows(JaredCategory.EVENING))
        assertFalse(defaults.allows(JaredCategory.PREFERENCE))
        assertTrue(defaults.allows(JaredCategory.RESULT))
    }

    @Test
    fun watchSafeBodyPrefersFirstLineThenFallsBackToCta() {
        val withBody = item(type = FeedItemType.MORNING_PLAN_READY, body = "3 focus blocks scheduled.\nmore detail")
        assertEquals("3 focus blocks scheduled.", JaredNotifier.watchSafeBody(withBody))

        val noBody = item(type = FeedItemType.PLAN_RUNNING_LATE, body = null)
        assertEquals("Tap to review Jared's suggestion.", JaredNotifier.watchSafeBody(noBody))
    }

    @Test
    fun watchSafeBodyClampsLongText() {
        val long = "x".repeat(200)
        val out = JaredNotifier.watchSafeBody(item(body = long))
        assertTrue(out.length <= 80)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun deepLinkBuildsDailyIntelligenceUrl() {
        // Trailing-slash origin (as ApiClientFactory normalizes) + a run id.
        assertEquals(
            "https://dash.ts.net/daily-intelligence?runId=7&feedItemId=42",
            JaredNotifier.dashboardDeepLink("https://dash.ts.net/", runId = 7, feedItemId = 42),
        )
        // No run id → only the feed item id.
        assertEquals(
            "https://dash.ts.net/daily-intelligence?feedItemId=42",
            JaredNotifier.dashboardDeepLink("https://dash.ts.net", runId = null, feedItemId = 42),
        )
        // No base URL → null (caller falls back to opening the app).
        assertNull(JaredNotifier.dashboardDeepLink("  ", runId = 1, feedItemId = 2))
    }

    private fun item(
        id: Long = 1,
        type: FeedItemType = FeedItemType.MORNING_PLAN_READY,
        body: String? = null,
    ) = JaredFeedItem(
        id = id,
        date = "2026-06-30",
        type = type,
        title = "t",
        body = body,
        status = FeedItemStatus.ACTIVE,
        relatedRunId = null,
    )
}
