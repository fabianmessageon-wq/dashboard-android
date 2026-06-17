package dev.jaredhq.dashboardandroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jaredhq.dashboardandroid.data.api.ApiClientFactory
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.data.settings.DeviceTokenFormat
import dev.jaredhq.dashboardandroid.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = "",
    val tokenInput: String = "",
    val hasToken: Boolean = false,
    val saved: Boolean = false,
    val testing: Boolean = false,
    val testResult: String? = null,
)

/**
 * Connection settings: the dashboard base URL (the user's Tailscale HTTPS origin)
 * and the per-device bearer token. The token is write-only from the UI's point of
 * view — once saved it is never read back into [SettingsUiState], only its
 * presence is shown — to keep it off the screen and out of state snapshots.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val repository: DashboardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    baseUrl = settings.baseUrlSnapshot(),
                    hasToken = settings.hasToken(),
                )
            }
        }
    }

    fun onBaseUrlChange(value: String) =
        _state.update { it.copy(baseUrl = value, saved = false, testResult = null) }

    fun onTokenChange(value: String) =
        _state.update { it.copy(tokenInput = value, saved = false, testResult = null) }

    /** Persist base URL, and the token only if a new one was typed. */
    fun save() {
        viewModelScope.launch { persist() }
    }

    /**
     * Persist the typed settings, normalizing the base URL to an origin. Returns
     * true on success; on a bad URL it sets a friendly [SettingsUiState.testResult]
     * and returns false. Suspends until persisted, so callers (e.g.
     * [testConnection]) can rely on storage being current — no save/refresh race.
     */
    private suspend fun persist(): Boolean {
        val raw = _state.value.baseUrl.trim()
        val normalized: String = if (raw.isEmpty()) {
            "" // Blank clears the URL → app falls back to sample/fake data.
        } else {
            try {
                ApiClientFactory.normalizeBaseUrl(raw)
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(saved = false, testResult = e.message ?: "Invalid URL.") }
                return false
            }
        }
        settings.setBaseUrl(normalized)
        val token = _state.value.tokenInput
        // Compute a non-blocking format hint before the token leaves the field.
        val tokenHint = if (token.isNotBlank()) DeviceTokenFormat.hintFor(token) else null
        if (token.isNotBlank()) settings.setToken(token)
        _state.update {
            it.copy(
                baseUrl = normalized,
                tokenInput = "",
                hasToken = settings.hasToken(),
                saved = true,
                // Surface the hint (if any); a clean save shows no message.
                testResult = tokenHint,
            )
        }
        return true
    }

    /** Clear the saved token (e.g. after revoking it on the dashboard). */
    fun clearToken() {
        viewModelScope.launch {
            settings.setToken(null)
            _state.update { it.copy(hasToken = false, testResult = null) }
        }
    }

    /**
     * Read-only live test of the saved config: exercises GET /today (+ /quote)
     * without mutating the server or the local cache. Maps the result to an
     * actionable message.
     */
    fun testConnection() {
        viewModelScope.launch {
            // Persist (and validate) the typed values FIRST so the probe below
            // uses the current base URL / token, then test.
            if (!persist()) return@launch
            if (settings.baseUrlSnapshot().isBlank()) {
                _state.update {
                    it.copy(testing = false, testResult = "Enter a dashboard URL to test the connection.")
                }
                return@launch
            }
            if (!settings.hasToken()) {
                _state.update {
                    it.copy(testing = false, testResult = "Add a device token before testing the connection.")
                }
                return@launch
            }
            _state.update { it.copy(testing = true, testResult = null) }
            val msg = when (val result = repository.testConnection()) {
                is DashboardRepository.ConnectionResult.Connected -> {
                    val quoteNote = if (result.quoteAvailable) "" else " (quote endpoint unavailable)"
                    "Connected — Today loaded for ${result.date}$quoteNote."
                }
                DashboardRepository.ConnectionResult.AuthFailed ->
                    "Auth failed (401/403) — check the device token and that its scope is \"actions\"."
                is DashboardRepository.ConnectionResult.Unreachable ->
                    "Couldn't reach the dashboard: ${result.message} Check the URL and that the dashboard is online."
            }
            _state.update { it.copy(testing = false, testResult = msg) }
        }
    }
}
