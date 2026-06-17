package dev.jaredhq.dashboardandroid.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jaredhq.dashboardandroid.ui.theme.DashboardTheme

/**
 * Capture screen — the fastest path to get a thought into the dashboard. Toggle
 * between the assistant (lets the dashboard decide task/note/event) and a direct
 * task capture.
 */
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    onInputChange: (String) -> Unit,
    onToggleAssistant: (Boolean) -> Unit,
    onSend: () -> Unit,
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.useAssistant,
                onClick = { onToggleAssistant(true) },
                label = { Text("Assistant") },
            )
            FilterChip(
                selected = !state.useAssistant,
                onClick = { onToggleAssistant(false) },
                label = { Text("Task only") },
            )
        }

        Button(
            onClick = onSend,
            enabled = !state.sending && state.input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.sending) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                Text("Sending…")
            } else {
                Text("Capture")
            }
        }

        state.lastReply?.let { reply ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(reply, Modifier.padding(12.dp))
            }
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

@Preview(showBackground = true)
@Composable
private fun PreviewCapture() {
    DashboardTheme {
        CaptureScreen(
            state = CaptureUiState(input = "Meeting with Sam Friday 2pm", lastReply = "Added an event for Friday 2pm."),
            onInputChange = {},
            onToggleAssistant = {},
            onSend = {},
        )
    }
}
