package dev.jaredhq.dashboardandroid.ui.watch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jaredhq.dashboardandroid.ble.WatchBatteryInfo
import dev.jaredhq.dashboardandroid.ble.WatchConnectionState
import dev.jaredhq.dashboardandroid.ui.AppViewModelFactory
import dev.jaredhq.dashboardandroid.ui.theme.DashboardTheme

/**
 * The Watch (BLE) screen — Phase 1 probe surface.
 *
 * Shows connection status, device info, battery, MTU, last command/response hex,
 * and a raw packet log. Stateless body ([WatchContent]) so Compose previews work
 * without a ViewModel.
 */
@Composable
fun WatchScreen(
    state: WatchUiState,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onMacRequest: () -> Unit,
    onDeviceInfoRequest: () -> Unit,
    onBatteryInfoRequest: () -> Unit,
    onCapturedStatusProbe: () -> Unit,
    onRawCommandChange: (String) -> Unit,
    onRawCommandSend: () -> Unit,
    onSyncClick: () -> Unit,
    onClearLog: () -> Unit,
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
) {
    WatchContent(
        state = state,
        onScanClick = onScanClick,
        onDisconnectClick = onDisconnectClick,
        onMacRequest = onMacRequest,
        onDeviceInfoRequest = onDeviceInfoRequest,
        onBatteryInfoRequest = onBatteryInfoRequest,
        onCapturedStatusProbe = onCapturedStatusProbe,
        onRawCommandChange = onRawCommandChange,
        onRawCommandSend = onRawCommandSend,
        onSyncClick = onSyncClick,
        onClearLog = onClearLog,
        onPermissionsGranted = onPermissionsGranted,
        onPermissionsDenied = onPermissionsDenied,
    )
}

@Composable
private fun WatchContent(
    state: WatchUiState,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onMacRequest: () -> Unit,
    onDeviceInfoRequest: () -> Unit,
    onBatteryInfoRequest: () -> Unit,
    onCapturedStatusProbe: () -> Unit,
    onRawCommandChange: (String) -> Unit,
    onRawCommandSend: () -> Unit,
    onSyncClick: () -> Unit,
    onClearLog: () -> Unit,
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
) {
    val context = LocalContext.current
    // BLUETOOTH_SCAN is declared neverForLocation in the manifest, so location
    // permission is not required for scanning on Android 12+.
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
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
        val allGranted = results.values.all { it }
        if (allGranted) onPermissionsGranted() else onPermissionsDenied()
    }

    LaunchedEffect(Unit) {
        // Check if permissions are already granted; if not, request them
        val allGranted = permissions.all {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            permissionLauncher.launch(permissions)
        } else {
            onPermissionsGranted()
        }
    }

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

        // Permission banner
        state.permissionRationale?.let {
            BannerCard(text = it, error = true)
        }

        // Sync confirmation banner
        state.syncMessage?.let {
            BannerCard(text = it, error = false)
        }

        // Connection status card
        ConnectionStatusCard(state.state)

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.state) {
                is WatchConnectionState.Disconnected,
                is WatchConnectionState.Error -> {
                    Button(
                        onClick = onScanClick,
                        enabled = state.hasPermissions,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Scan & Connect")
                    }
                }
                is WatchConnectionState.Scanning -> {
                    OutlinedButton(
                        onClick = onDisconnectClick,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Stop Scan")
                    }
                }
                is WatchConnectionState.Connecting -> {
                    OutlinedButton(
                        onClick = onDisconnectClick,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                }
                is WatchConnectionState.Connected -> {
                    OutlinedButton(
                        onClick = onDisconnectClick,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }

        // Command buttons (only when connected). Writes are disabled until the watch is
        // ready (notification setup complete) so a tap can't race the CCCD chain.
        if (state.state is WatchConnectionState.Connected) {
            val ready = (state.state as WatchConnectionState.Connected).ready
            Text(
                text = if (ready) "Commands" else "Commands (waiting for notification setup…)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onMacRequest, enabled = ready, modifier = Modifier.weight(1f)) {
                    Text("MAC (301)")
                }
                OutlinedButton(onClick = onDeviceInfoRequest, enabled = ready, modifier = Modifier.weight(1f)) {
                    Text("Info (300)")
                }
                OutlinedButton(onClick = onBatteryInfoRequest, enabled = ready, modifier = Modifier.weight(1f)) {
                    Text("Battery (321)")
                }
            }

            OutlinedButton(
                onClick = onCapturedStatusProbe,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Captured Probe (02:01)")
            }

            OutlinedTextField(
                value = state.rawCommandHex,
                onValueChange = onRawCommandChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Raw hex command") },
                placeholder = { Text("AB 01 41 01 00 00 00 C3 F7") },
            )
            OutlinedButton(
                onClick = onRawCommandSend,
                enabled = ready && state.rawCommandHex.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send Raw Hex")
            }

            // Device info display
            DeviceInfoCard(state.state)

            // Upload connection/device telemetry to the dashboard (Phase 2).
            Button(
                onClick = onSyncClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sync to Dashboard")
            }
        }

        // Raw packet log
        Text(
            text = "Raw Packet Log",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = state.rawLog.ifBlank { "No packets logged yet." },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Watch raw packet log", state.rawLog))
                    Toast.makeText(context, "Raw packet log copied", Toast.LENGTH_SHORT).show()
                },
                enabled = state.rawLog.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Copy Logs")
            }
            OutlinedButton(
                onClick = onClearLog,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear Log")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectionStatusCard(state: WatchConnectionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is WatchConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                is WatchConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is WatchConnectionState.Disconnected -> {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    Text("Disconnected", style = MaterialTheme.typography.titleMedium)
                }
                is WatchConnectionState.Scanning -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Scanning…", style = MaterialTheme.typography.titleMedium)
                }
                is WatchConnectionState.Connecting -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Connecting to ${state.deviceAddress}…", style = MaterialTheme.typography.titleMedium)
                }
                is WatchConnectionState.Connected -> {
                    if (state.ready) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    } else {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Column {
                        Text(
                            if (state.ready) "Connected" else "Initialising…",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.deviceName ?: state.deviceAddress,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is WatchConnectionState.Error -> {
                    Text("Error: ${state.reason}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(state: WatchConnectionState.Connected) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Device Info", style = MaterialTheme.typography.labelMedium)
            InfoRow("Address", state.deviceAddress)
            InfoRow("Name", state.deviceName ?: "—")
            InfoRow("Battery", state.batteryInfo?.let { "${it.level}% (${WatchBatteryInfo.statusString(it.status)})" } ?: "—")
            InfoRow("Voltage", state.batteryInfo?.let { if (it.voltage > 0) "${it.voltage} mV" else "—" } ?: "—")
            InfoRow("Last charge", state.batteryInfo?.lastChargingTime ?: "—")
            InfoRow("MTU", "${state.mtu}")
            InfoRow("MAC (301)", state.macAddress ?: "—")
            InfoRow("Last CMD", state.lastCommandHex ?: "—")
            InfoRow("Last RX", state.lastResponseHex ?: "—")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BannerCard(text: String, error: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Previews (no ViewModel) ─────────────────────────────────────────────────

@Preview(name = "Watch — disconnected", showBackground = true)
@Composable
private fun PreviewDisconnected() {
    DashboardTheme {
        WatchScreen(
            state = WatchUiState(),
            onScanClick = {},
            onDisconnectClick = {},
            onMacRequest = {},
            onDeviceInfoRequest = {},
            onBatteryInfoRequest = {},
            onCapturedStatusProbe = {},
            onRawCommandChange = {},
            onRawCommandSend = {},
            onSyncClick = {},
            onClearLog = {},
            onPermissionsGranted = {},
            onPermissionsDenied = {},
        )
    }
}

@Preview(name = "Watch — connected", showBackground = true)
@Composable
private fun PreviewConnected() {
    DashboardTheme {
        WatchScreen(
            state = WatchUiState(
                state = WatchConnectionState.Connected(
                    deviceAddress = "AA:BB:CC:DD:EE:FF",
                    deviceName = "Kogan Active 4 Pro",
                    ready = true,
                    batteryInfo = WatchBatteryInfo(level = 78, status = 0, voltage = 4200, mode = 0),
                    mtu = 247,
                    macAddress = "AA:BB:CC:DD:EE:FF",
                ),
                hasPermissions = true,
                rawLog = "[12:34:56.789] TX: 7B 7D\n[12:34:56.812] RX: 7B 22 6C 65 76 65 6C 22 3A 37 38 7D",
            ),
            onScanClick = {},
            onDisconnectClick = {},
            onMacRequest = {},
            onDeviceInfoRequest = {},
            onBatteryInfoRequest = {},
            onCapturedStatusProbe = {},
            onRawCommandChange = {},
            onRawCommandSend = {},
            onSyncClick = {},
            onClearLog = {},
            onPermissionsGranted = {},
            onPermissionsDenied = {},
        )
    }
}
