package dev.jaredhq.dashboardandroid.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production [SettingsStore] backed by [EncryptedSharedPreferences]: the device
 * token is encrypted at rest with a key held in the Android Keystore, so it is
 * not readable from a rooted backup or by another app. (Base URL is not secret
 * but lives in the same encrypted file for simplicity.)
 *
 * Why EncryptedSharedPreferences over DataStore here: DataStore has no built-in
 * encryption, and the security-crypto library gives Keystore-backed AES for free
 * with a SharedPreferences API. Reads/writes are synchronous and cheap (single
 * small file), wrapped to satisfy the suspend contract.
 *
 * NOTE: requires `androidx.security:security-crypto`. If a future AGP/Tink
 * conflict arises, the fallback is a DataStore + manual Keystore-wrapped key;
 * the [SettingsStore] interface insulates the rest of the app from that choice.
 */
class SecureSettingsStore(context: Context) : SettingsStore {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _baseUrl = MutableStateFlow(
        prefs.getString(SettingsKeys.BASE_URL, SettingsKeys.DEFAULT_BASE_URL).orEmpty(),
    )
    private val _hasToken = MutableStateFlow(
        !prefs.getString(SettingsKeys.DEVICE_TOKEN, null).isNullOrBlank(),
    )

    // In-memory mirror so the OkHttp interceptor can read the token without
    // suspending or hitting disk on every request.
    @Volatile
    private var tokenMirror: String? =
        prefs.getString(SettingsKeys.DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() }

    override val baseUrlFlow: StateFlow<String> = _baseUrl.asStateFlow()
    override val hasTokenFlow: StateFlow<Boolean> = _hasToken.asStateFlow()

    override suspend fun setBaseUrl(url: String) {
        val cleaned = url.trim()
        prefs.edit().putString(SettingsKeys.BASE_URL, cleaned).apply()
        _baseUrl.value = cleaned
    }

    override suspend fun setToken(token: String?) {
        val cleaned = token?.trim().orEmpty()
        prefs.edit().apply {
            if (cleaned.isBlank()) remove(SettingsKeys.DEVICE_TOKEN)
            else putString(SettingsKeys.DEVICE_TOKEN, cleaned)
            apply()
        }
        tokenMirror = cleaned.takeIf { it.isNotBlank() }
        _hasToken.value = cleaned.isNotBlank()
    }

    override suspend fun readToken(): String? = tokenSnapshot()

    override fun tokenSnapshot(): String? = tokenMirror

    override fun baseUrlSnapshot(): String = _baseUrl.value

    override suspend fun hasToken(): Boolean = !tokenMirror.isNullOrBlank()

    override suspend fun clear() {
        prefs.edit().clear().apply()
        tokenMirror = null
        _baseUrl.value = SettingsKeys.DEFAULT_BASE_URL
        _hasToken.value = false
    }

    private companion object {
        // Excluded from backup (see res/xml/backup_rules.xml).
        const val PREFS_FILE = "dashboard_secure_settings"
    }
}
