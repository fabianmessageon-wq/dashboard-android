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
    onStatusRequest: () -> Unit,
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
        onStatusRequest = onStatusRequest,
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
    onStatusRequest: () -> Unit,
    onClearLog: () -> Unit,
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
) {
    val context = LocalContext.current
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
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

        // Command buttons (only when connected)
        if (state.state is WatchConnectionState.Connected) {
            Text(
                text = "Commands",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onMacRequest, modifier = Modifier.weight(1f)) {
                    Text("MAC (02:04)")
                }
                OutlinedButton(onClick = onDeviceInfoRequest, modifier = Modifier.weight(1f)) {
                    Text("Info (02:02)")
                }
                OutlinedButton(onClick = onStatusRequest, modifier = Modifier.weight(1f)) {
                    Text("Status (02:07)")
                }
            }

            // Device info display
            DeviceInfoCard(state.state)
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
        OutlinedButton(onClick = onClearLog) {
            Text("Clear Log")
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
                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    Column {
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
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
            InfoRow("Battery", state.batteryPercent?.let { "$it%" } ?: "—")
            InfoRow("MTU", "${state.mtu}")
            InfoRow("MAC (02:04)", state.macAddress ?: "—")
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
            onStatusRequest = {},
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
                    batteryPercent = 78,
                    mtu = 247,
                    macAddress = "AA:BB:CC:DD:EE:FF",
                ),
                hasPermissions = true,
                rawLog = "[12:34:56.789] TX: 02 04 00 06\n[12:34:56.812] RX: 02 04 0C AA BB CC DD EE FF AA BB CC DD EE FF 3A",
            ),
            onScanClick = {},
            onDisconnectClick = {},
            onMacRequest = {},
            onDeviceInfoRequest = {},
            onStatusRequest = {},
            onClearLog = {},
            onPermissionsGranted = {},
            onPermissionsDenied = {},
        )
    }
}
