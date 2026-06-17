package dev.jaredhq.dashboardandroid.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * Holds the two pieces of connection config the user enters in Settings:
 *  - [baseUrlFlow]: the dashboard origin (their Tailscale HTTPS URL, e.g.
 *    `https://dashboard.tailnet.ts.net`).
 *  - the per-device bearer token, which is SECRET and stored encrypted.
 *
 * The token is intentionally NOT exposed as a Flow or returned in plaintext to
 * UI state — only [hasToken] and a one-shot [readToken] for the networking
 * layer — to minimize the surface where it could leak into logs/snapshots.
 */
interface SettingsStore {
    val baseUrlFlow: Flow<String>
    val hasTokenFlow: Flow<Boolean>

    suspend fun setBaseUrl(url: String)

    /** Store the raw device token securely. Pass null/blank to clear it. */
    suspend fun setToken(token: String?)

    /** One-shot read for the auth interceptor. Never log the result. */
    suspend fun readToken(): String?

    /**
     * Synchronous token read for the OkHttp interceptor, which runs off the main
     * thread and cannot suspend. Backed by an in-memory mirror of the encrypted
     * value. Never log the result.
     */
    fun tokenSnapshot(): String?

    /** Synchronous base-URL read for building the client without suspending. */
    fun baseUrlSnapshot(): String

    suspend fun hasToken(): Boolean

    /** Wipe everything (sign-out / token revoked). */
    suspend fun clear()
}

object SettingsKeys {
    const val BASE_URL = "base_url"
    const val DEVICE_TOKEN = "device_token"
    const val DEFAULT_BASE_URL = ""
}
