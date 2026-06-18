package dev.jaredhq.dashboardandroid.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jaredhq.dashboardandroid.data.api.ApiException
import dev.jaredhq.dashboardandroid.data.repository.DashboardRepository
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.domain.model.CaptureMode
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureUiState(
    val input: String = "",
    val sending: Boolean = false,
    /** When true, send via the intelligent /chat endpoint; else direct /capture. */
    val useAssistant: Boolean = true,

    // ── Result of the last successful send (all cleared when a new send starts) ──
    /** Friendly one-line headline: what happened (assistant ran / fell back / saved). */
    val statusMessage: String? = null,
    /** The assistant's own reply text, when /chat returned one. */
    val lastReply: String? = null,
    /** How the server interpreted the last send (assistant / fallback / direct). */
    val lastMode: CaptureMode? = null,
    /** Tool names the assistant executed, e.g. ["create_event"]. */
    val lastActions: List<String> = emptyList(),
    /** Task id created by a direct/fallback capture, when present. */
    val lastCreatedTaskId: Int? = null,
    /** Items the assistant is waiting on the user to confirm (assistant mode only). */
    val pendingConfirmation: List<String> = emptyList(),

    /** User-friendly failure message (no raw internals). */
    val error: String? = null,
    /** Non-error notice about speech input (unavailable / nothing heard). */
    val speechNotice: String? = null,
) {
    /** True once the last send produced a result worth showing. */
    val hasResult: Boolean get() = statusMessage != null
}

/**
 * Quick-capture: type or dictate a thought, send it. Two paths mirror the server:
 *  - assistant (/chat): the dashboard agent decides task/note/event/etc.,
 *  - direct (/capture): always creates a task (offline-safe, deterministic).
 *
 * The assistant path can fall back to a deterministic task when model calls are
 * off, so the UI must show *which* happened — that is what the explicit result
 * fields on [CaptureUiState] carry (the old single `lastReply` hid it, which made
 * a working fallback look like nothing happened).
 */
class CaptureViewModel(
    private val repository: DashboardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun onInputChange(value: String) =
        _state.update { it.copy(input = value, error = null, speechNotice = null) }

    fun setUseAssistant(value: Boolean) {
        // Ignore toggles mid-send so the in-flight request's mode can't change.
        if (_state.value.sending) return
        _state.update { it.copy(useAssistant = value) }
    }

    fun send() {
        // Snapshot input + mode BEFORE any state mutation so the in-flight request
        // is immune to the user editing the field or flipping the toggle.
        val text = _state.value.input.trim()
        val useAssistant = _state.value.useAssistant
        if (text.isEmpty() || _state.value.sending) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    sending = true,
                    error = null,
                    speechNotice = null,
                    statusMessage = null,
                    lastReply = null,
                    lastMode = null,
                    lastActions = emptyList(),
                    lastCreatedTaskId = null,
                    pendingConfirmation = emptyList(),
                )
            }

            val result = if (useAssistant) repository.chat(text) else repository.capture(text)
            result.fold(
                onSuccess = { r ->
                    _state.update {
                        it.copy(
                            sending = false,
                            input = "",
                            statusMessage = statusFor(r),
                            lastReply = r.reply?.takeIf { reply -> reply.isNotBlank() },
                            lastMode = r.mode,
                            lastActions = r.actions,
                            lastCreatedTaskId = r.createdTaskId,
                            pendingConfirmation = r.pendingConfirmation,
                        )
                    }
                    // The repository already cached the fresh Today; reflect it on the
                    // home-screen widget now rather than waiting for the periodic worker.
                    ServiceLocator.refreshWidgetFromCache()
                },
                onFailure = { e ->
                    _state.update { it.copy(sending = false, error = friendlyError(e)) }
                },
            )
        }
    }

    // ── Speech-to-text (transcript handed in from the Capture route) ────────────

    /**
     * Merge a recognized transcript into the input rather than replacing it, so a
     * user who typed a few words then dictated the rest keeps both. The user still
     * reviews/edits and taps Capture — speech never auto-sends.
     */
    fun applyTranscript(transcript: String) {
        val clean = transcript.trim()
        if (clean.isEmpty()) return
        _state.update {
            val merged = if (it.input.isBlank()) clean else "${it.input.trimEnd()} $clean"
            it.copy(input = merged, error = null, speechNotice = null)
        }
    }

    /** No recognizer on the device, or the recognizer activity couldn't launch. */
    fun onSpeechUnavailable() = _state.update {
        it.copy(speechNotice = "Speech recognition isn't available on this device — you can still type.")
    }

    /** Recognizer returned but produced no usable text (user cancelled or silence). */
    fun onSpeechNoResult() = _state.update {
        it.copy(speechNotice = "Didn't catch that — try again or type it.")
    }

    fun dismissSpeechNotice() = _state.update { it.copy(speechNotice = null) }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun statusFor(r: CaptureResult): String = when (r.mode) {
        CaptureMode.ASSISTANT ->
            if (r.pendingConfirmation.isNotEmpty()) {
                "Assistant needs you to confirm before continuing."
            } else {
                "Assistant handled this."
            }
        CaptureMode.TASK_FALLBACK -> "AI is off — saved as a task${taskSuffix(r.createdTaskId)}."
        CaptureMode.DIRECT -> "Saved as a task${taskSuffix(r.createdTaskId)}."
        CaptureMode.UNKNOWN -> "Captured."
    }

    private fun taskSuffix(id: Int?): String = id?.let { " (#$it)" } ?: ""

    /**
     * Map a failure to a calm, actionable message. Reads the [ApiException] status
     * when present; never surfaces raw exception text (which can leak URLs, stack
     * details, or decode internals).
     */
    private fun friendlyError(e: Throwable): String {
        val api = e as? ApiException
        return when {
            api == null -> "Something went wrong. Please try again."
            api.isAuthError -> "Your dashboard token was rejected. Update it in Settings."
            api.status == 0 -> "Couldn't reach the dashboard. Check your connection and URL."
            api.status == 502 -> "The assistant is unavailable right now. Try \"Task only\", or try again later."
            api.status in 500..599 -> "The dashboard had a problem (${api.status}). Please try again."
            api.status == 400 -> "That couldn't be sent. Try rephrasing your capture."
            else -> "Couldn't capture that (${api.status}). Please try again."
        }
    }
}
