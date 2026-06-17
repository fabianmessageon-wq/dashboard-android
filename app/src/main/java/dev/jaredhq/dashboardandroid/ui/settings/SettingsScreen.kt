package dev.jaredhq.dashboardandroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jaredhq.dashboardandroid.ui.theme.DashboardTheme

/**
 * Settings — base URL + device token entry, plus guidance on minting/revoking a
 * token in the dashboard's Settings → Devices page. The token field is masked
 * and write-only; we only ever show whether a token is stored.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onClearToken: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Dashboard URL") },
            placeholder = { Text("https://dashboard.your-tailnet.ts.net") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        OutlinedTextField(
            value = state.tokenInput,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.hasToken) "Replace device token" else "Device token") },
            placeholder = { Text("dwtk_…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Text(
            text = if (state.hasToken) {
                "A token is stored securely. Leave the field blank to keep it."
            } else {
                "No token stored yet."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text("Save") }
            OutlinedButton(onClick = onTest, enabled = !state.testing) {
                if (state.testing) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("Testing…")
                } else {
                    Text("Test connection")
                }
            }
            if (state.hasToken) {
                OutlinedButton(onClick = onClearToken) { Text("Clear token") }
            }
        }

        if (state.saved) {
            Text("Saved.", style = MaterialTheme.typography.bodyMedium)
        }
        state.testResult?.let { result ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(result, Modifier.padding(12.dp))
            }
        }

        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Getting a device token", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1. Open your dashboard in a browser → Settings → Devices.\n" +
                        "2. Create a token (name it e.g. \"Pixel widget\"). Choose the " +
                        "\"actions\" scope to allow habit toggle / focus / capture.\n" +
                        "3. Copy the token shown once (prefix dwtk_) and paste it above.\n" +
                        "4. To revoke: delete the device in Settings → Devices, then " +
                        "Clear token here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSettings() {
    DashboardTheme {
        SettingsScreen(
            state = SettingsUiState(
                baseUrl = "https://dashboard.example.ts.net",
                hasToken = true,
                testResult = "Connected — Today loaded for 2026-06-17.",
            ),
            onBaseUrlChange = {},
            onTokenChange = {},
            onSave = {},
            onClearToken = {},
            onTest = {},
        )
    }
}
