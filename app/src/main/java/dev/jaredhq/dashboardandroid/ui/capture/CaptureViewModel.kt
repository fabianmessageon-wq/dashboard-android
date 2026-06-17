package dev.jaredhq.dashboardandroid.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.domain.model.CaptureMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureUiState(
    val input: String = "",
    val sending: Boolean = false,
    val lastReply: String? = null,
    val error: String? = null,
    /** When true, send via the intelligent /chat endpoint; else direct /capture. */
    val useAssistant: Boolean = true,
)

/**
 * Quick-capture: type a thought, send it. Two paths mirror the server:
 *  - assistant (/chat): the dashboard agent decides task/note/event/etc.,
 *  - direct (/capture): always creates a task (offline-safe, deterministic).
 */
class CaptureViewModel(
    private val repository: DashboardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun onInputChange(value: String) = _state.update { it.copy(input = value, error = null) }

    fun setUseAssistant(value: Boolean) = _state.update { it.copy(useAssistant = value) }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null, lastReply = null) }
            if (_state.value.useAssistant) {
                repository.chat(text).fold(
                    onSuccess = { result ->
                        val note = when (result.mode) {
                            CaptureMode.TASK_FALLBACK -> " (AI off — saved as task)"
                            else -> ""
                        }
                        _state.update {
                            it.copy(sending = false, input = "", lastReply = (result.reply ?: "Captured.") + note)
                        }
                    },
                    onFailure = { e -> _state.update { it.copy(sending = false, error = e.message) } },
                )
            } else {
                repository.capture(text).fold(
                    onSuccess = {
                        _state.update { it.copy(sending = false, input = "", lastReply = "Saved as a task.") }
                    },
                    onFailure = { e -> _state.update { it.copy(sending = false, error = e.message) } },
                )
            }
        }
    }
}
