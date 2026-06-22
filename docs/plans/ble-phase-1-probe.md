# Phase 1 BLE Probe — Implementation Plan

## Goal

Build a minimal BLE proof-of-concept in dashboard-android that can connect to the Kogan Active 4 Pro smartwatch (VeryFit/IDO BLE protocol), perform basic protocol handshake, read battery, and log raw events.

> ## ✅ VERIFIED ON REAL HARDWARE (2026-06-22) — read first
>
> Phase 1 is **complete and proven on-device.** Built with Android Studio's bundled
> JDK 17 + Gradle 8.10.2, installed on a Samsung Galaxy S21 (Android 14) over ADB
> Wi-Fi, and run against the real Kogan Active 4 Pro. Every Phase-1 objective passed
> (logcat tag `WatchBLE`):
>
> | Step | Result |
> |------|--------|
> | Scan | Matched watch at static address `F4:91:29:51:C6:45` (advert carried no name) |
> | Connect | `onConnectionStateChange status=0 newState=2` |
> | MTU | Negotiated **247** |
> | Service discovery | `0x0AF0` found; AF6 (0xe) / AF7 (0x12) / AF2 (0x12) / AF1 (0xe) |
> | Notifications | CCCD on AF7 + AF2 → `onDescriptorWrite status=0` (AF1 has no notify bit, skipped) |
> | Write | `02 04`, `02 01`, `02 A7` → `onCharacteristicWrite status=0` |
> | RX | MAC `F4:91:29:51:C6:45` parsed; **battery 90%** from both `02:01` and `02:A7` |
>
> **The 2026-06-21 "Correction" below is itself now DISPROVEN by an on-device
> btsnoop capture of the official VeryFit app.** The real wire protocol *does* use
> short 2-byte commands — `02 04` (MAC), `02 01` (status), `02 A7` (battery) — not
> opaque binary IDO frames for these requests. `WatchProtocol.kt` was corrected to
> build and parse these watch-verified commands; the `AB`-header IDO V3 frame
> builder is retained only for future/long-sync frames. Battery byte offsets:
> `02:01` response byte[7], `02:A7` response byte[5] (both `0x5A` = 90% live).
>
> **Two operational notes for reproducing:**
> 1. The watch is **dual-mode**. While its Classic BR/EDR link (HID/Handsfree/A2DP)
>    is active, `connectGatt(TRANSPORT_LE)` returns **status=133**. Remedy (works):
>    `adb shell cmd bluetooth_manager disable` then `enable`, then connect.
> 2. Short `07 40 1F 00…` transport-ACK frames must NOT be parsed as battery — the
>    raw heuristic would misread `0x07` as "7%". The GATT callback now only trusts
>    the binary battery heuristic on CRC-valid frames (regression test added).
>
> ---
>
> ## ⚠ Correction (2026-06-21) — SUPERSEDED by the 2026-06-22 capture above
>
> This plan was written before the native protocol boundary was confirmed. Two of
> its original assumptions are **superseded**; the current `ble/` source no longer
> follows them. Apply these corrections wherever the steps below still say "02:04"
> or "0x180F":
>
> 1. ~~**No raw `02:04`/`02:02`/`02:07` two-byte commands.**~~ **DISPROVEN** — the
>    btsnoop capture shows the watch *does* accept and answer 2-byte commands. The
>    JSON↔native boundary reasoning held for the long binary sync frames, but the
>    basic status/identity requests are simple 2-byte opcodes. Original (now
>    incorrect) reasoning kept for history: *the official app calls
>    `Protocol.WriteJsonData(json, type)` and the native lib frames bytes into a
>    binary IDO packet; JSON exists only at the Java↔native boundary.*
> 2. **Battery does not come from the standard Battery Service.** `0x180F`/`0x2A19`
>    is only "if the watch exposes it"; on this watch it was not a reliable source,
>    so the implementation **removed the 0x180F read** and requests battery over the
>    IDO protocol instead. ✅ Confirmed: battery comes from the `02:01`/`02:A7`
>    notification responses on `0x0AF7`, not from a standard Battery Service.
>
> What remains correct: the UUIDs, the connect→MTU→notify(0x0AF7/0x0AF2)→write(0x0AF6)
> sequence, the developer raw-event logger, and the Phase 2 telemetry upload.

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
| Battery service *(only if exposed — see note)* | `0000180F-0000-1000-8000-00805f9b34fb` |
| Battery characteristic *(only if exposed — see note)* | `00002A19-0000-1000-8000-00805f9b34fb` |

> The standard Battery Service is listed because the APK references it generically,
> **not** because the Active 4 Pro reliably exposes it. The implementation does not
> depend on it; battery is requested over the IDO protocol. See the Correction above.

## Phase 1 Objectives

1. Scan for and connect to Active 4 Pro
2. Discover service `0x0AF0`
3. Request MTU 517, expect negotiated 247
4. Enable notifications on `0x0AF7` and `0x0AF2`
5. ~~Write `02:04` command to `0x0AF6`, confirm MAC response~~ → Write a **binary IDO
   V3 frame** to `0x0AF6` (request cmd/key UNVERIFIED — capture-gated; see Correction)
6. ~~Read battery via standard GATT `0x180F`~~ → Request battery over the **IDO
   protocol** and parse the binary notification; do **not** depend on `0x180F`
7. Log raw protocol events (developer-only)
8. Display connection status and basic telemetry in UI

## Architecture

### New Files

```
app/src/main/java/dev/jaredhq/dashboardandroid/
├── ble/
│   ├── WatchBleManager.kt          # High-level manager: scan, connect, sync
│   ├── WatchGattCallback.kt        # BluetoothGattCallback implementation
│   ├── WatchProtocol.kt            # Binary IDO V3 frame builders/parsers (cmd/key UNVERIFIED)
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
- `onServicesDiscovered`: find `0x0AF0`, enable notifications (no `0x180F` battery read)
- `onCharacteristicChanged` (notification): log raw bytes, parse the binary IDO frame
  (structured breakdown), extract battery from the payload
- `onMtuChanged`: record negotiated MTU
- `onDescriptorWrite`: confirm CCCD enabled, then issue the battery request

### Step 4: Protocol Commands

> ✅ **Watch-verified (2026-06-22).** The 2-byte opcodes ARE the real transport for
> status/identity (the 2026-06-21 "superseded" note was wrong — see the verification
> block at the top). The shipped `WatchProtocol`:

- `buildMacAddressCommand()` → `02 04`; response `02 04 <mac×2>`, MAC = bytes[2..7]
  (`parseMacAddressFromCapturedMacResponse`). **Watch-verified.**
- `buildBatteryInfoCommand()` → `02 A7`; response battery = byte[5]
  (`parseBatteryInfoFromCapturedBatteryPoll`). **Watch-verified.**
- `buildCapturedStatusProbeCommand()` → `02 01`; response battery = byte[7]
  (`parseBatteryInfoFromCapturedStatus`). **Watch-verified.**
- `buildRequestFrame(dataType)` / `buildFrame(...)` → assembles an `AB`-header binary
  IDO V3 frame `head | cmd | key | seq | version | len | payload | crc16`. **Retained
  for the still-unverified long `0x33` health-sync frames**, not used for the basic
  status/identity requests above.
- `parseFrame(bytes)` → structured view (head/cmd/key/len/payload/CRC, both CRC16
  variants) for diagnostic logging of the long frames.
- `parseBatteryInfoFromBinary` (heuristic) — now gated behind `frame.crcValid` in the
  GATT callback so transport ACKs like `07 40 1F 00…` aren't misread as "7% battery".

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
- Device info: name, MAC address, battery % (status/voltage), MTU
- Command buttons: Battery (321), Info (300), MAC (301) — each sends a binary IDO
  frame (UNVERIFIED cmd/key), not a raw 2-byte opcode
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

## Verification Steps — ✅ ALL COMPLETE (2026-06-22)

1. ✅ `./gradlew testDebugUnitTest` — all tests pass
2. ✅ `./gradlew assembleDebug` — builds
3. ✅ Installed on Samsung Galaxy S21 (Android 14) over ADB Wi-Fi
4. ✅ Bluetooth on, watch nearby (and VeryFit `com.watch.life` force-stopped)
5. ✅ Opened Watch tab, tapped Scan
6. ✅ Watch matched (by static MAC `F4:91:29:51:C6:45` — it advertises with no name,
   so the keyword filter alone would miss it; the MAC fallback is what catches it)
7. ✅ Connected
8. ✅ Status: scanning → connecting → connected
9. ✅ MTU 247
10. ✅ Raw events logged; each notification logs a structured breakdown, and the
    watch-verified `02:01`/`02:A7`/`02:04` responses decode to battery/MAC
11. ✅ **Capture step DONE (the real unblock):** an on-device btsnoop capture of the
    official VeryFit app was analysed (`tshark` over `btsnoop_hci.log`). It locked
    the real commands: write `02 04`→MAC, `02 01`→status(battery byte[7]),
    `02 A7`→battery(byte[5]). `WatchProtocol.kt` now uses these watch-verified
    values, and battery/MAC display is **confirmed**, not best-effort.

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
- Bluetooth capture analysis: a fresh on-device btsnoop (`btsnoop_hci.log`, captured
  2026-06-22 from the official VeryFit app on the S21) **was** obtained and analysed
  with `tshark`. It locked the watch-verified commands now in `WatchProtocol.kt`
  (`02 04` MAC, `02 01` status, `02 A7` battery) and was re-confirmed against the
  app's own live traffic. The earlier session `20260621_010233_b4f7a6` artifacts were
  not present; this newer capture supersedes that gap.
