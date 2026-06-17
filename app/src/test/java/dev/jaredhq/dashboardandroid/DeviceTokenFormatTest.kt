package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.settings.DeviceTokenFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the non-binding device-token format check used in Settings. */
class DeviceTokenFormatTest {

    @Test
    fun acceptsAWellFormedToken() {
        // dwtk_ + ~43 base64url chars (32 bytes).
        val token = "dwtk_" + "A".repeat(43)
        assertTrue(DeviceTokenFormat.isLikelyValid(token))
        assertNull(DeviceTokenFormat.hintFor(token))
    }

    @Test
    fun trimsBeforeChecking() {
        val token = "  dwtk_" + "B".repeat(30) + "  "
        assertTrue(DeviceTokenFormat.isLikelyValid(token))
    }

    @Test
    fun flagsMissingPrefix() {
        val hint = DeviceTokenFormat.hintFor("abc123def456ghi789jkl012")
        assertNotNull(hint)
        assertTrue(hint!!.contains("dwtk_"))
        assertFalse(DeviceTokenFormat.isLikelyValid("abc123def456ghi789jkl012"))
    }

    @Test
    fun flagsTooShortEvenWithPrefix() {
        assertFalse(DeviceTokenFormat.isLikelyValid("dwtk_short"))
        assertNotNull(DeviceTokenFormat.hintFor("dwtk_short"))
    }

    @Test
    fun blankYieldsNoHint() {
        assertNull(DeviceTokenFormat.hintFor(""))
        assertNull(DeviceTokenFormat.hintFor("   "))
    }
}
