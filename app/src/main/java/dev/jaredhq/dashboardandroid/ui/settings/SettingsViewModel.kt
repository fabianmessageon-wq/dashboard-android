package dev.jaredhq.dashboardandroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
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
        viewModelScope.launch {
            val s = _state.value
            settings.setBaseUrl(s.baseUrl)
            if (s.tokenInput.isNotBlank()) settings.setToken(s.tokenInput)
            _state.update {
                it.copy(
                    tokenInput = "",
                    hasToken = settings.hasToken(),
                    saved = true,
                )
            }
        }
    }

    /** Clear the saved token (e.g. after revoking it on the dashboard). */
    fun clearToken() {
        viewModelScope.launch {
            settings.setToken(null)
            _state.update { it.copy(hasToken = false, testResult = null) }
        }
    }

    /** Hit GET /today with the saved config and report success/failure. */
    fun testConnection() {
        viewModelScope.launch {
            // Make sure the latest typed values are saved before testing.
            save()
            _state.update { it.copy(testing = true, testResult = null) }
            val outcome = repository.refreshToday()
            val msg = when {
                outcome.source == DashboardRepository.DataSource.NETWORK ->
                    "Connected — Today loaded for ${outcome.payload?.date ?: "today"}."
                outcome.authError -> "Auth failed — check the device token."
                else -> outcome.error ?: "Could not reach the dashboard."
            }
            _state.update { it.copy(testing = false, testResult = msg) }
        }
    }
}
