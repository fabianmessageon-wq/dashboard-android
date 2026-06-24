package dev.jaredhq.dashboardandroid.ui.watch

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jaredhq.dashboardandroid.ui.theme.DashboardTheme
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState.AWAITING_WATCH_CONFIRMATION
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState.BINDING
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState.CONNECTED
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState.CONNECTING
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState.DISCONNECTED
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState.SCANNING
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState.SYNCING

/**
 * The product Watch screen — connection + health sync, driven by the [WatchEngine] boundary
 * (the vendored-SDK engine today). Distinct from the clean-room BLE debug console
 * ([WatchScreen]/[WatchViewModel]), which is retained as a dev-only tool.
 *
 * Requests Bluetooth permissions on first show, then surfaces the connection lifecycle and lets
 * the user connect/disconnect and trigger a sync; a synced run's record counts confirm data flowed.
 */
@Composable
fun WatchHealthScreen(
    state: WatchHealthUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
) {
    val context = LocalContext.current
    // BLUETOOTH_SCAN is declared neverForLocation in the manifest, so no location permission is
    // needed for scanning on Android 12+.
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) onPermissionsGranted() else onPermissionsDenied()
    }
    LaunchedEffect(Unit) {
        val allGranted = permissions.all {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) onPermissionsGranted() else permissionLauncher.launch(permissions)
    }

    WatchHealthContent(state, onConnect, onDisconnect, onSync)
}

@Composable
private fun WatchHealthContent(
    state: WatchHealthUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Watch",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        state.permissionRationale?.let { BannerCard(it, error = true) }

        ConnectionStatusCard(state.connection)

        if (state.connection == AWAITING_WATCH_CONFIRMATION) {
            BannerCard("Tap to confirm the pairing on your watch face.", error = false)
        }

        ActionButtons(state, onConnect, onDisconnect, onSync)

        if (state.syncing) {
            SyncProgressCard(state)
        }

        state.lastSync?.let { LastSyncCard(it) }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionButtons(
    state: WatchHealthUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.connection) {
            DISCONNECTED -> Button(
                onClick = onConnect,
                enabled = state.hasPermissions,
                modifier = Modifier.weight(1f),
            ) { Text("Connect") }

            SCANNING, CONNECTING, BINDING, AWAITING_WATCH_CONFIRMATION -> OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
            ) { Text("Cancel") }

            CONNECTED -> {
                Button(onClick = onSync, modifier = Modifier.weight(1f)) { Text("Sync now") }
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) { Text("Disconnect") }
            }

            SYNCING -> OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
            ) { Text("Disconnect") }
        }
    }
}

@Composable
private fun ConnectionStatusCard(connection: WatchEngineConnectionState) {
    val (label, detail) = connection.describe()
    val connected = connection == CONNECTED || connection == SYNCING
    val busy = connection == SCANNING || connection == CONNECTING ||
        connection == BINDING || connection == SYNCING
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = when {
                        connected -> Icons.Filled.BluetoothConnected
                        connection == DISCONNECTED -> Icons.Filled.BluetoothDisabled
                        else -> Icons.Filled.Bluetooth
                    },
                    contentDescription = null,
                )
            }
            Column {
                Text(label, style = MaterialTheme.typography.titleMedium)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SyncProgressCard(state: WatchHealthUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Syncing…", style = MaterialTheme.typography.titleMedium)
            Text(
                "${state.liveCounts.total} records received so far",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LastSyncCard(summary: WatchSyncSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Last sync", style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (summary.succeeded) "Completed at ${summary.at}"
                else "Finished at ${summary.at} (ended with a warning)",
                style = MaterialTheme.typography.titleMedium,
            )
            val c = summary.counts
            if (c.total == 0) {
                Text("No new records.", style = MaterialTheme.typography.bodyMedium)
            } else {
                CountRow("Workouts", c.workouts)
                CountRow("Sleep sessions", c.sleepSessions)
                CountRow("Activity days", c.activityDays)
                CountRow("Heart-rate days", c.heartRateDays)
                CountRow("SpO₂ samples", c.spo2)
                CountRow("HRV samples", c.hrv)
                CountRow("Respiratory samples", c.respiratory)
                CountRow("Temperature samples", c.temperature)
                CountRow("Body-energy samples", c.bodyEnergy)
            }
        }
    }
}

/** A metric row, shown only when the count is non-zero. */
@Composable
private fun CountRow(label: String, count: Int) {
    if (count <= 0) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text("$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BannerCard(text: String, error: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Friendly (label, detail?) for each lifecycle state. */
private fun WatchEngineConnectionState.describe(): Pair<String, String?> = when (this) {
    DISCONNECTED -> "Disconnected" to "Connect to sync your watch's health data."
    SCANNING -> "Searching…" to "Looking for your watch. Keep it nearby and awake."
    CONNECTING -> "Connecting…" to null
    BINDING -> "Pairing…" to "Claiming the watch for this app."
    AWAITING_WATCH_CONFIRMATION -> "Confirm on watch" to "Waiting for you to tap to allow on the watch."
    CONNECTED -> "Connected" to "Ready to sync."
    SYNCING -> "Syncing…" to "Transferring health data."
}

// ── Previews ────────────────────────────────────────────────────────────────

@Preview(name = "Watch — disconnected", showBackground = true)
@Composable
private fun PreviewDisconnected() {
    DashboardTheme {
        WatchHealthContent(
            state = WatchHealthUiState(hasPermissions = true),
            onConnect = {}, onDisconnect = {}, onSync = {},
        )
    }
}

@Preview(name = "Watch — synced", showBackground = true)
@Composable
private fun PreviewSynced() {
    DashboardTheme {
        WatchHealthContent(
            state = WatchHealthUiState(
                connection = CONNECTED,
                hasPermissions = true,
                lastSync = WatchSyncSummary(
                    at = "08:14:02",
                    succeeded = true,
                    counts = WatchSyncCounts(
                        sleepSessions = 1, workouts = 2, spo2 = 96, hrv = 40,
                        respiratory = 96, temperature = 96, bodyEnergy = 96,
                    ),
                ),
            ),
            onConnect = {}, onDisconnect = {}, onSync = {},
        )
    }
}
