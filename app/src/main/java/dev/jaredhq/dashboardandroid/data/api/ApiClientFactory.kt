package dev.jaredhq.dashboardandroid.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds a live [DashboardApiClient] from a base URL and a token provider.
 *
 * - JSON is lenient (`ignoreUnknownKeys`) so the server can add fields without
 *   breaking older app builds — important for a long-lived mobile client.
 * - The Authorization header is redacted from logs.
 * - The base URL is normalized to end in `/` (Retrofit requirement).
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

    /** Retrofit requires the base URL to end with `/`. */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
