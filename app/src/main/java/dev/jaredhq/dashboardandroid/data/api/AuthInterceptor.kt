package dev.jaredhq.dashboardandroid.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <device-token>` to every request. The token is
 * read fresh per request from [tokenProvider] so revoking/replacing it in
 * Settings takes effect immediately without rebuilding the OkHttp client.
 *
 * SECURITY: the token is never logged here. The OkHttp logging interceptor (see
 * [ApiClientFactory]) is configured to redact the Authorization header.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
