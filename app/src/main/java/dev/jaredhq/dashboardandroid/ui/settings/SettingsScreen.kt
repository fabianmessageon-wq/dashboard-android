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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jaredhq.dashboardandroid.notify.NotificationAccess
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

        DailyIntelligenceCard(baseUrl = state.baseUrl)

        WatchNotificationMirrorCard()

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

/**
 * Self-contained control for the W7 calls/texts → watch mirror. Notification access can't be
 * requested programmatically, so this shows the current grant state and deep-links to the system
 * screen; it re-checks on resume so returning from Settings reflects the new state immediately.
 */
@Composable
private fun WatchNotificationMirrorCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = NotificationAccess.isGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mirror calls & texts to watch", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (granted) {
                    "On. Incoming calls and texts show on the watch while it's connected."
                } else {
                    "Off. Grant notification access so calls and texts can be forwarded to the watch."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { context.startActivity(NotificationAccess.settingsIntent()) }) {
                Text(if (granted) "Notification access settings" else "Grant notification access")
            }
        }
    }
}

/**
 * Links out to the dashboard's Daily Intelligence ("Jared") settings page, where
 * the master/per-category push toggles live (the Android bridge honours them via
 * GET /api/daily-intelligence/settings). We don't duplicate those controls on the
 * phone — the web page is the source of truth; this is just a deep link to it.
 * Disabled until a dashboard URL is set, since there's nowhere to open otherwise.
 */
@Composable
private fun DailyIntelligenceCard(baseUrl: String) {
    val context = LocalContext.current
    val origin = remember(baseUrl) {
        runCatching { dev.jaredhq.dashboardandroid.data.api.ApiClientFactory.normalizeBaseUrl(baseUrl) }
            .getOrNull()
            ?.trimEnd('/')
    }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Daily Intelligence (Jared)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Jared posts your morning plan, mid-day nudges, and evening reflection as " +
                    "notifications on this phone. Choose which categories notify you on the " +
                    "dashboard's Daily Intelligence settings page.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                enabled = origin != null,
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("$origin/daily-intelligence"),
                    )
                    runCatching { context.startActivity(intent) }
                },
            ) {
                Text("Open Jared settings")
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
