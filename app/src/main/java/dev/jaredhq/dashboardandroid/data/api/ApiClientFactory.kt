package dev.jaredhq.dashboardandroid.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Builds a live [DashboardApiClient] from a base URL and a token provider.
 *
 * - JSON is lenient (`ignoreUnknownKeys`) so the server can add fields without
 *   breaking older app builds — important for a long-lived mobile client.
 * - The Authorization header is redacted from logs.
 * - The base URL is validated and normalized to an ORIGIN with a trailing `/`
 *   (see [normalizeBaseUrl]) so the `api/widget/v1/...` paths resolve correctly.
 */
object ApiClientFactory {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun create(
        baseUrl: String,
        tokenProvider: () -> String?,
        enableLogging: Boolean = false,
    ): DashboardApiClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (enableLogging) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // Never let the bearer token reach logcat, even in debug.
            redactHeader("Authorization")
        }

        val ok = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return RetrofitDashboardApiClient(retrofit.create(DashboardService::class.java))
    }

    /**
     * Validate and normalize a user-entered URL to an **origin** with a trailing
     * slash (`scheme://host[:port]/`):
     *
     *  - requires an `http`/`https` scheme (HTTPS strongly preferred — see docs;
     *    `http` is tolerated only for local-dev convenience),
     *  - requires a host,
     *  - strips any path / query / fragment, so the endpoint paths resolve as
     *    `…/api/widget/v1/today` rather than under a user-typed subpath,
     *  - is idempotent (re-normalizing a normalized value is a no-op).
     *
     * Throws [IllegalArgumentException] with a user-friendly message on bad input;
     * callers (Settings, the repository) catch it and surface the message instead
     * of crashing.
     */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Enter a dashboard URL." }
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("That doesn't look like a valid URL.")
        }
        val scheme = uri.scheme?.lowercase()
            ?: throw IllegalArgumentException("Start the URL with https:// (e.g. https://dashboard.your-tailnet.ts.net).")
        require(scheme == "http" || scheme == "https") {
            "URL must start with https:// (or http:// for local dev)."
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The URL is missing a host name.")
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$port/"
    }
}
