package dev.jaredhq.dashboardandroid.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the Today screen — a single immutable snapshot the screen renders. */
data class TodayUiState(
    val loading: Boolean = false,
    val payload: TodayPayload? = null,
    val fromCache: Boolean = false,
    val error: String? = null,
    val authError: Boolean = false,
    /** A habit/focus action currently in flight, for per-row spinners. */
    val pendingHabitId: Int? = null,
    val focusInFlight: Boolean = false,
)

/**
 * Drives the Today screen. Offline-first: it shows the cached payload instantly,
 * then refreshes. Mutations call the repository and replace the whole payload
 * with the server's fresh Today (the contract), so the screen never patches.
 */
class TodayViewModel(
    private val repository: DashboardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState(loading = true))
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        // Paint the cache immediately so first frame isn't blank, then refresh.
        viewModelScope.launch {
            repository.cachedToday()?.let { cached ->
                _state.update { it.copy(payload = cached, fromCache = true) }
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val outcome = repository.refreshToday()
            _state.update {
                it.copy(
                    loading = false,
                    payload = outcome.payload ?: it.payload,
                    fromCache = outcome.source != DashboardRepository.DataSource.NETWORK,
                    error = outcome.error,
                    authError = outcome.authError,
                )
            }
        }
    }

    fun toggleHabit(habitId: Int) {
        viewModelScope.launch {
            _state.update { it.copy(pendingHabitId = habitId, error = null) }
            val result = repository.toggleHabit(habitId)
            _state.update { st ->
                result.fold(
                    onSuccess = { fresh -> st.copy(payload = fresh, fromCache = false, pendingHabitId = null) },
                    onFailure = { e -> st.copy(error = e.message, pendingHabitId = null) },
                )
            }
        }
    }

    fun startFocus() {
        val payload = _state.value.payload
        val taskId = payload?.focusBlock?.taskId ?: payload?.mainAction?.taskId
        viewModelScope.launch {
            _state.update { it.copy(focusInFlight = true, error = null) }
            val result = repository.startFocus(taskId = taskId)
            _state.update { st ->
                result.fold(
                    onSuccess = { fresh -> st.copy(payload = fresh, fromCache = false, focusInFlight = false) },
                    onFailure = { e -> st.copy(error = e.message, focusInFlight = false) },
                )
            }
        }
    }
}
