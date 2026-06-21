# Phase 1 BLE Probe — Implementation Plan

## Goal

Build a minimal BLE proof-of-concept in dashboard-android that can connect to the Kogan Active 4 Pro smartwatch (VeryFit/IDO BLE protocol), perform basic protocol handshake, read battery, and log raw events.

## Context

- **Watch:** Kogan Active 4 Pro, uses VeryFit protocol (IDO BLE SDK)
- **APK:** `com.watch.life` v3.4.0 — reverse engineered to confirm UUIDs and command families
- **Dashboard Android app:** Native Kotlin + Jetpack Compose, minSdk 26, compileSdk 34
- **Current state:** No BLE module exists; app has 3 tabs (Today, Capture, Settings)

## Confirmed BLE UUIDs (from APK reverse engineering)

| Purpose | UUID |
|--------|------|
| Main service | `00000aF0-0000-1000-8000-00805f9b34fb` |
| Write characteristic | `00000aF6-0000-1000-8000-00805f9b34fb` |
| Notify characteristic | `00000aF7-0000-1000-8000-00805f9b34fb` |
| Secondary notify | `00000aF2-0000-1000-8000-00805f9b34fb` |
| Extra/encryption | `00000aF8-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| Battery service | `0000180F-0000-1000-8000-00805f9b34fb` |
| Battery characteristic | `00002A19-0000-1000-8000-00805f9b34fb` |

## Phase 1 Objectives

1. Scan for and connect to Active 4 Pro
2. Discover service `0x0AF0`
3. Request MTU 517, expect negotiated 247
4. Enable notifications on `0x0AF7` and `0x0AF2`
5. Write `02:04` command to `0x0AF6`, confirm MAC response
6. Read battery via standard GATT `0x180F`
7. Log raw protocol events (developer-only)
8. Display connection status and basic telemetry in UI

## Architecture

### New Files

```
app/src/main/java/dev/jaredhq/dashboardandroid/
├── ble/
│   ├── WatchBleManager.kt          # High-level manager: scan, connect, sync
│   ├── WatchGattCallback.kt        # BluetoothGattCallback implementation
│   ├── WatchProtocol.kt            # Command builders (02:04, 02:02, etc.)
│   ├── WatchPacketLogger.kt        # Raw packet logging (developer-only)
│   └── WatchConnectionState.kt     # Sealed class for UI state
├── data/api/dto/
│   └── WatchSyncDto.kt             # DTO for uploading raw events to dashboard
├── domain/model/
│   └── WatchConnection.kt          # Domain model: device, status, battery, lastSeen
├── ui/watch/
│   ├── WatchScreen.kt              # New tab: scan, connect, status
│   └── WatchViewModel.kt           # State management for watch tab
└── work/
    └── WatchSyncWorker.kt          # Periodic background sync attempt
```

### Modified Files

- `AndroidManifest.xml` — BLE permissions
- `MainActivity.kt` — Add 4th "Watch" tab to bottom navigation
- `di/ServiceLocator.kt` — Provide WatchBleManager
- `ui/AppViewModelFactory.kt` — Add WatchViewModel case

## Implementation Steps

### Step 1: Manifest & Permissions

Add to `AndroidManifest.xml`:
- `android.permission.BLUETOOTH` (maxSdkVersion 30)
- `android.permission.BLUETOOTH_ADMIN` (maxSdkVersion 30)
- `android.permission.BLUETOOTH_SCAN` (Android 12+)
- `android.permission.BLUETOOTH_CONNECT` (Android 12+)
- `android.permission.ACCESS_FINE_LOCATION`
- `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />`

Runtime permission handling for Android 12+ (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`).

### Step 2: BLE Manager

`WatchBleManager`:
- Wrapper around `BluetoothLeScanner` and `BluetoothGatt`
- Scan filter for name "Active 4 Pro" or service UUID `0x0AF0`
- Connect with `autoConnect=false` initially
- Expose `StateFlow<WatchConnectionState>`
- Handle Android 12+ permission model

### Step 3: GATT Callback

`WatchGattCallback` (extends `BluetoothGattCallback`):
- `onConnectionStateChange`: track CONNECTED/DISCONNECTED
- `onServicesDiscovered`: find `0x0AF0`, read battery `0x180F`
- `onCharacteristicChanged` (notification): log to `WatchPacketLogger`
- `onMtuChanged`: record negotiated MTU
- `onDescriptorWrite`: confirm CCCD enabled
- `onCharacteristicRead`: parse battery level

### Step 4: Protocol Commands

`WatchProtocol`:
- `buildCommand02_04(): ByteArray` → `byteArrayOf(0x02, 0x04)` — returns watch MAC
- `buildCommand02_02(): ByteArray` → `byteArrayOf(0x02, 0x02)` — device info
- `buildCommand02_07(): ByteArray` → `byteArrayOf(0x02, 0x07)` — status
- `buildEnableNotify(): ByteArray` → `byteArrayOf(0x01, 0x00)` for CCCD
- MAC parser: extracts 6-byte MAC from doubled response

### Step 5: Packet Logger

`WatchPacketLogger`:
- Thread-safe in-memory ring buffer (max 200 entries)
- Fields: timestamp, direction (phone→watch / watch→phone), characteristic UUID, hex payload
- Developer-only, never persisted to disk or server beyond session

### Step 6: UI

Add 4th "Watch" tab to `MainActivity` bottom navigation (using `Icons.Filled.Bluetooth`).

`WatchScreen` shows:
- Scan button (with permission rationale if needed)
- Connection status card (disconnected/scanning/connecting/connected/error)
- Device info: name, MAC address, battery %, MTU
- Command buttons: Send 02:04, Send 02:02, Send 02:07
- Last command sent + last response received (hex)
- Raw packet log viewer (scrollable, developer-only)
- Disconnect button

`WatchViewModel`:
- `StateFlow<WatchUiState>` matching existing Today/Capture pattern
- Collects BLE state from `WatchBleManager`
- Formats packet log for display

### Step 7: Dashboard Upload (Placeholder)

`WatchSyncDto`:
```kotlin
@Serializable
data class WatchSyncDto(
    val deviceId: String,
    val connectedAt: String,
    val batteryPercent: Int?,
    val mtu: Int?,
    val connectionState: String,
    val rawEvents: List<RawEventDto>
)
```

Upload endpoint: `POST /api/widget/v1/watch/sync` (dashboard side to be implemented in Phase 2).

### Step 8: Background Worker

`WatchSyncWorker` (CoroutineWorker):
- Periodic attempt to connect and sync
- Phase 1: manual trigger only (Android BLE restrictions for background)
- Phase 2: foreground service for auto-sync

## Verification Steps

1. `./gradlew testDebugUnitTest` — existing tests pass
2. `./gradlew assembleDebug`
3. Install on Samsung S21
4. Enable Bluetooth, ensure watch is nearby
5. Open Watch tab, tap Scan
6. Verify "Active 4 Pro" appears in scan results
7. Tap Connect
8. Verify status changes: scanning → connecting → connected
9. Verify battery % appears from `0x180F`
10. Verify MTU shows 247
11. Tap "Send 02:04", verify MAC response logged
12. Verify raw events appear in packet log

## Privacy & Security

- Raw packet log is developer-only, never persisted beyond app session
- Contact payloads (if any D1 traffic appears) are redacted and not stored
- No health metrics decoded or uploaded in Phase 1
- BLE connection data is user-local until explicitly uploaded

## Next Phases

Phase numbering follows the authoritative roadmap in `docs/plans/ble-master-plan.md`
(an earlier draft of this list conflated Phases 2 and 3 — corrected below).

### Phase 2: Safe Dashboard Metrics — ✅ Android side complete

**Objective:** Upload connection/device telemetry (device, battery, MTU, connection
state, developer-only raw handshake events) to the dashboard. **No health metrics**
— those are Phase 3.

This is the natural completion of Step 7 (Dashboard Upload) above: the `WatchSyncDto`
placeholder is now a real upload path.

**Implemented in this repo:**
- `WatchSyncRequest`/`WatchSyncEvent`/`WatchSyncResult` domain models +
  `WatchSyncDto`/`WatchRawEventDto`/`WatchSyncResponseDto` wire DTOs (request body
  matches the master plan).
- `WatchSyncMapper` builds the payload from the live `WatchConnectionState`
  (MAC-preferred device id, lowercase connection-state, ISO-8601 timestamps).
- `WatchPacketLogger` captures structured `RawEvent`s (TX in
  `WatchBleManager.writeCommand`, RX in `WatchGattCallback`).
- `DashboardApiClient.syncWatch` → `POST /api/widget/v1/watch/sync`, wired through
  the Retrofit + Fake clients and `DashboardRepository`.
- `WatchSyncWorker` + `WatchSyncScheduler`: one-off upload on every connection event
  (via `WatchBleManager.onConnectionEvent`) and a 6-hour periodic refresh; manual
  "Sync to Dashboard" button on the Watch screen.
- Unit tests: `WatchSyncMappingTest`, plus `syncWatch` cases in `RepositoryTest`.

**Dashboard side (separate repo, not in this codebase):** the `/api/widget/v1/watch/sync`
endpoint, the `watch_devices`/`watch_connections`/`watch_raw_events` schema, and the
Settings device card + developer raw-event viewer. See master plan Phase 2.

### Later phases (not in scope)

- Phase 3: Health metric schema (steps, heart rate, sleep, etc.) + `/watch/health` API
- Phase 4: Packet reassembly + decoding for the `0x33` and `0xD1` families
- Phase 5: Clean-room sync command reverse-engineering / native library decision
- Phase 6+: Health UI, background sync (foreground service), advanced features

## References

- PKS note: `engineering/reviews/active-4-pro-veryfit-ble-protocol-investigation-initial-apk-findings.md`
- APK: `/home/apolytus/workspace/veryfit-3-4-0.apk`
- Bluetooth capture analysis: session `20260621_010233_b4f7a6`
