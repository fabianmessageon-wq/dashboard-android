package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.data.api.ApiClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure-JVM tests for [ApiClientFactory.normalizeBaseUrl] — the boundary that
 * keeps a malformed URL from reaching (and crashing) Retrofit, and that strips a
 * user-typed path so `api/widget/v1/...` resolves against the origin.
 */
class BaseUrlNormalizationTest {

    @Test
    fun addsTrailingSlashToOrigin() {
        assertEquals(
            "https://dashboard.tailnet.ts.net/",
            ApiClientFactory.normalizeBaseUrl("https://dashboard.tailnet.ts.net"),
        )
    }

    @Test
    fun stripsPathQueryAndFragment() {
        assertEquals(
            "https://host.example/",
            ApiClientFactory.normalizeBaseUrl("https://host.example/some/path?x=1#frag"),
        )
    }

    @Test
    fun preservesPort() {
        assertEquals(
            "https://host.example:8443/",
            ApiClientFactory.normalizeBaseUrl("https://host.example:8443/api"),
        )
    }

    @Test
    fun isIdempotent() {
        val once = ApiClientFactory.normalizeBaseUrl("https://host.example/a/b")
        assertEquals(once, ApiClientFactory.normalizeBaseUrl(once))
    }

    @Test
    fun allowsHttpForLocalDevWhenCleartextEnabled() {
        assertEquals(
            "http://10.0.2.2:3000/",
            ApiClientFactory.normalizeBaseUrl("http://10.0.2.2:3000", allowCleartext = true),
        )
    }

    @Test
    fun rejectsHttpWhenCleartextDisabled() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiClientFactory.normalizeBaseUrl("http://10.0.2.2:3000", allowCleartext = false)
        }
    }

    @Test
    fun rejectsMissingScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiClientFactory.normalizeBaseUrl("dashboard.tailnet.ts.net")
        }
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiClientFactory.normalizeBaseUrl("ftp://host.example")
        }
    }

    @Test
    fun rejectsBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiClientFactory.normalizeBaseUrl("   ")
        }
    }
}
