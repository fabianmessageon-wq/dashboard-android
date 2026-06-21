package dev.jaredhq.dashboardandroid.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jaredhq.dashboardandroid.domain.model.CaptureMode
import dev.jaredhq.dashboardandroid.ui.theme.DashboardTheme

/**
 * Capture screen — the fastest path to get a thought into the dashboard. Type or
 * dictate, optionally let the assistant interpret it (else a direct task), and
 * send. Dictation fills the input for review/edit; it never auto-sends.
 */
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    onInputChange: (String) -> Unit,
    onToggleAssistant: (Boolean) -> Unit,
    onSend: () -> Unit,
    speechAvailable: Boolean = true,
    onStartSpeech: () -> Unit = {},
    onDismissSpeechNotice: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Quick capture", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What's on your mind?") },
            placeholder = { Text("e.g. Book dentist next week") },
            minLines = 3,
            enabled = !state.sending,
        )

        // Dictation — fills the input above, which the user reviews before sending.
        OutlinedButton(
            onClick = onStartSpeech,
            enabled = speechAvailable && !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = if (speechAvailable) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (speechAvailable) "  Speak" else "  Speech unavailable",
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        state.speechNotice?.let { notice ->
            // Tapping the notice dismisses it — otherwise it lingers until the
            // next edit/send (it was previously unreachable from the UI).
            Card(
                onClick = onDismissSpeechNotice,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    "$notice  (tap to dismiss)",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.useAssistant,
                onClick = { onToggleAssistant(true) },
                enabled = !state.sending,
                label = { Text("Assistant") },
            )
            FilterChip(
                selected = !state.useAssistant,
                onClick = { onToggleAssistant(false) },
                enabled = !state.sending,
                label = { Text("Task only") },
            )
        }

        Button(
            onClick = onSend,
            enabled = !state.sending && state.input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.sending) {
                CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                Text("Sending…")
            } else {
                Text("Capture")
            }
        }

        if (state.hasResult) {
            ResultCard(state)
        }
        state.error?.let { err ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(err, Modifier.padding(12.dp))
            }
        }
    }
}

/**
 * The outcome of the last send: a friendly headline (what happened) plus the
 * assistant reply, any tools it ran, pending confirmations, and a created task
 * id — so a fallback or a confirm-needed result is unmistakable, not silent.
 */
@Composable
private fun ResultCard(state: CaptureUiState) {
    val needsConfirm = state.pendingConfirmation.isNotEmpty()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (needsConfirm) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            // Don't repeat the reply if it's identical to the headline.
            state.lastReply?.takeIf { it != state.statusMessage }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (needsConfirm) {
                Text(
                    "Waiting on: ${state.pendingConfirmation.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.lastActions.isNotEmpty()) {
                Text(
                    "Actions: ${state.lastActions.joinToString(", ")}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Capture — assistant result")
@Composable
private fun PreviewCaptureAssistant() {
    DashboardTheme {
        CaptureScreen(
            state = CaptureUiState(
                input = "",
                statusMessage = "Assistant handled this.",
                lastReply = "Added an event for Friday 2pm.",
                lastMode = CaptureMode.ASSISTANT,
                lastActions = listOf("create_event"),
            ),
            onInputChange = {},
            onToggleAssistant = {},
            onSend = {},
        )
    }
}

@Preview(showBackground = true, name = "Capture — task fallback")
@Composable
private fun PreviewCaptureFallback() {
    DashboardTheme {
        CaptureScreen(
            state = CaptureUiState(
                input = "",
                useAssistant = true,
                statusMessage = "AI is off — saved as a task (#87).",
                lastMode = CaptureMode.TASK_FALLBACK,
                lastCreatedTaskId = 87,
            ),
            onInputChange = {},
            onToggleAssistant = {},
            onSend = {},
        )
    }
}

@Preview(showBackground = true, name = "Capture — speech unavailable")
@Composable
private fun PreviewCaptureSpeechUnavailable() {
    DashboardTheme {
        CaptureScreen(
            state = CaptureUiState(
                input = "Buy milk",
                speechNotice = "Speech recognition isn't available on this device — you can still type.",
            ),
            onInputChange = {},
            onToggleAssistant = {},
            onSend = {},
            speechAvailable = false,
        )
    }
}
