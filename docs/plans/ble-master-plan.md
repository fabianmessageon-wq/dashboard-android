# Active 4 Pro BLE Integration — Master Plan

> ## ⚠ Direction changed 2026-06-24 — read [ADR 0001](../adr/0001-vendor-ido-sdk.md) first
>
> The watch integration moved from the **clean-room-first** approach this document describes to
> a **hybrid: vendor the IDO/VeryFit SDK now, clean-room high-value paths later** (health-first,
> private-only). The decompiled APK proved the real wire protocol lives in native libs
> (`libVeryFitMulti.so`), so lifting the SDK gets full functionality fast.
>
> This plan is **kept as protocol/architecture reference and as the clean-room-later target** —
> its UUIDs, native-boundary findings, `0x33`/`0xD1` notes, type-id table, and capture workflow
> remain accurate and useful. But the *execution order* is now the watch slice ladder in
> [`docs/roadmap.md`](../roadmap.md), and the production data path is the vendored SDK behind
> `WatchEngine` / `IdoSdkWatchEngine`, not a per-metric clean-room reimplementation. The existing
> clean-room code (`WatchBleManager`/`WatchProtocol`) is retained as `CleanRoomWatchEngine`.

## Goal

Build an independent Android-dashboard bridge for Fabian's Kogan Active 4 Pro smartwatch, avoiding VeryFit/vendor/cloud/third-party health platforms for the private build. The watch data flows: Active 4 Pro → dashboard-android app → Fabian's self-hosted dashboard API.

## Product direction — Fabian-private build first

This plan now prioritizes Fabian's private/personal dashboard before any monetized or general-user product. Optimize for Fabian's watch, phone, dashboard, and private AI/Jared reasoning. Do not slow the current implementation down with commercial onboarding, generic smartwatch certification, app-store polish, enterprise health-compliance scaffolding, or broad multi-device abstractions unless they directly help Fabian's own watch work reliably.

A monetized product track is deferred. If it happens later, it should be treated as a separate fork/decision: standard health integrations such as Google Health/Health Connect/iHealth for general users, or a supported-compatible-smartwatch/direct-sync model. The current branch should stay small, practical, and privacy-first.

## Current private-build status (after `ble-stability-ready-lifecycle`)

The `ble-stability-ready-lifecycle` work has been merged to `origin/main` and brought into `phone-integration-refinement`. Current developed state:

- `WatchConnectionState.Connected.ready` distinguishes GATT-connected from safe-to-write-ready.
- `WatchBleManager.writeCommand()` rejects writes until notification setup is complete.
- `WatchBleManager` serializes GATT writes with `commandLock`, `pendingCommands`, and `writeInProgress`.
- `WatchGattCallback` chains notification/indication setup, handles missing CCCDs without hanging, and uses indication CCCD values for indicate-only characteristics.
- `0x0AF8` is read before notification setup when readable.
- `02 01` basic-info parsing now has `WatchBasicInfo`, including `device_id` little-endian parsing and `firmware_version` at offset 4.
- Basic-info `firmware_version == 1` is deliberately decoupled from activity payload version `16`.
- Activity version helpers exist, but `33 DA AD` activity reassembly/decoding is not implemented yet.

The old broad phases below remain useful as reference, but the immediate execution order is the Fabian-private phase list in the next section.

## Fabian-private implementation phases

### Private Phase 1 — real-phone ready-lifecycle validation

Validate the current branch on Fabian's real phone/watch before stacking more protocol work:

- fresh APK deploy via Android Studio/Gradle/ADB,
- force-stop VeryFit,
- scan/connect/service discovery/MTU,
- `0x0AF8` read when present,
- serial notification/indication setup,
- transition to `ready=true`,
- automatic `02 01` probe,
- parsed `WatchBasicInfo` and battery visible/logged.

Acceptance: Fabian's watch reaches `ready=true`, `02 01` parses from real watch data, no early-write/GATT race symptoms appear, and logs are copyable enough for iteration.

### Private Phase 2 — personal activity buffer capture

Implement only `33 DA AD` activity chunk reassembly:

- detect `0x33` notification chunks,
- detect preamble `DA AD DA AD` at bytes 1..4,
- read little-endian length from bytes 6..7,
- append bytes from offset 1 onward,
- detect complete buffers,
- log `activityVersion()` / `describeActivityVersion()`,
- keep unsupported versions non-fatal.

Do not decode full fields or generate TCX yet.

### Private Phase 3 — personal activity summary decode

Decode only summary fields needed for Fabian's private timeline: start time, type, duration, steps, distance, calories, and heart-rate summary when present. Use reference simulator bytes and fresh watch captures as tests. Keep confidence labels with each decoded field.

### Private Phase 4 — private dashboard timeline

Sync/display useful personal watch trends: device profile, battery history, connection/last-seen, activity summaries, daily/weekly trend cards, manual refresh/sync, last-updated, and confidence indicators.

### Private Phase 5 — Jared/AI health-pattern layer

Let Jared reason over Fabian's private health/activity trends once the data is trustworthy. Keep output in wellness/pattern-support territory, not diagnosis or medical advice.

### Private Phase 6 — optional metric expansion for Fabian only

Only after the activity loop is useful, consider sleep, SpO2, stress/pressure, HRV, respiratory rate, temperature, and GPS/path data. Each metric still needs capture-observed or watch-verified evidence before normal UI/AI use.

### Private Phase 7 — product fork decision later

After the private build proves value, decide separately whether monetization means standard health-platform integrations, a compatible-smartwatch bundle/direct-sync model, or a split between private Jared HQ and public dashboard product.

> ## ✅ Evidence update (2026-06-22) — supersedes the 2026-06-21 correction below
>
> A fresh on-device btsnoop capture (`btsnoop_hci.log`, analysed with `tshark`) plus a
> live run of the dashboard app on a Galaxy S21 against the real watch **resolved
> several previously-"unknown"/"disproven" rows** — and reversed one of them:
>
> | Claim | Updated status (2026-06-22) |
> |---|---|
> | `02:04` / `02:01` / `02:A7` raw 2-byte commands | ✅ **WATCH-VERIFIED as the real transport for status/identity.** This *reverses* the 2026-06-21 "disproven" row: the watch genuinely accepts these 2-byte writes on `0AF6` and answers on `0AF7`. `02 04`→MAC, `02 01`→status (battery byte[7]), `02 A7`→battery (byte[5]). Confirmed in capture **and** live. |
> | Battery without standard `0x180F` | ✅ **Confirmed** — battery comes from the `02:01`/`02:A7` responses, two samples (95% capture, 90% live) confirming the byte offsets |
> | MAC/device identity | ✅ **Confirmed** — `02 04` returns the MAC `F4:91:29:51:C6:45` (matches the BLE address) |
> | `0x33 / DA AD DA AD` long frames | Still **binary, unverified internals** — the long health-sync frames remain to be decoded (Stage 2 instrumentation) |
> | Wire is binary IDO frames, JSON only at native boundary | Holds **for the long sync frames**, but the basic status/identity requests are simple 2-byte opcodes, not opaque native-framed packets |
>
> Net: Phase 1 (connect + status/battery/MAC) is **done and verified on hardware**.
> The binary `0x33`/`0xD1` health families and broad metrics are still capture-gated.
>
> ---
>
> ## ⚠ Evidence correction (2026-06-21) — partially superseded (see 2026-06-22 above)
>
> This plan predates confirmation of the native protocol boundary. The APK proves a
> specific architecture that changes how several phases must be implemented. Honest
> status of the key assumptions, used throughout the phases below:
>
> | Claim in this plan | Status | Evidence |
> |---|---|---|
> | BLE UUIDs (`0x0AF0`/`0AF6`/`0AF7`/`0AF2`) | **Confirmed** | `com.ido.ble.bluetooth.utils.frost`; native `protocoDataF2` |
> | Native sync entry points (`StartSyncHealthData`, `WriteJsonData`, …) | **Confirmed (Java↔native API)** | JNI exports in `libVeryFitMulti.so` |
> | Health **type IDs** (1–20, 7001–7019) | **Confirmed as native/JSON-boundary IDs** | native strings + `SyncV3Handler` |
> | Wire is **binary IDO frames**, JSON only at the native boundary | **Confirmed for long sync frames** | `WriteJsonData(json)`→native frames bytes; `ReceiveDatafromBle(byte[])` takes raw binary; `CallBackJsonData` returns JSON |
> | ~~`02:04`/`02:02`/`02:07` raw 2-byte commands disproven~~ | **↑ REVERSED 2026-06-22 — these ARE the real transport for status/identity (see update above)** | on-device btsnoop + live run |
> | Battery via standard `0x180F`/`0x2A19` | **Not reliable on this watch** | notes say "if readable"; impl removed the read and uses the IDO battery request |
> | `0x33 / DA AD DA AD` frame + CRC-16/CCITT-FALSE | **Plausible but unverified here** | CRC matches native `crc16_compute`; frame shape is consistent with native `head fixed`/`cmd`/`key`/`nseq`/`len` strings; per-field/per-command meaning still uncaptured |
> | Exact per-request **cmd/key**, head byte, payload structs (long frames) | **Unknown — requires focused capture/instrumentation** | live inside the stripped native lib; not in any string |
> | Which metrics this watch actually supports | **Must be capability-gated** | native `support_*` / function-table feature flags; APK schema ≠ device support |
>
> Rule of thumb for the still-unverified parts: **"APK-confirmed at the native
> boundary" ≠ "verified on the BLE wire."** Treat the long `0x33`/`0xD1` frames and
> per-metric availability as **capture-gated / capability-gated** until a focused
> capture or the device function table proves it.

## Context

- **Watch:** Kogan Active 4 Pro (rebranded IDO/VeryFit device)
- **Official app:** VeryFit (`com.watch.life` v3.4.0), uses IDO BLE SDK with native protocol library (`libVeryFitMulti.so`)
- **Dashboard Android app:** Native Kotlin + Jetpack Compose, minSdk 26, compileSdk 34
- **Protocol:** BLE GATT, service `0x0AF0`, custom binary protocol. The `0x02` family two-byte commands (`02 04` MAC, `02 01` status, `02 A7` battery) are **watch-verified** (capture + live run, 2026-06-22). The longer `0x33`/`0xD1` health-sync families remain **capture-observed/hypothesised** and need decoding (see Evidence update).
- **APK location:** `/home/apolytus/workspace/veryfit-3-4-0.apk`
- **PKS investigation note:** `engineering/reviews/active-4-pro-veryfit-ble-protocol-investigation-initial-apk-findings.md`

## Confirmed BLE UUIDs

| Purpose | UUID |
|--------|------|
| Main service | `00000aF0-0000-1000-8000-00805f9b34fb` |
| Write characteristic | `00000aF6-0000-1000-8000-00805f9b34fb` |
| Notify characteristic | `00000aF7-0000-1000-8000-00805f9b34fb` |
| Secondary write-ish | `00000aF1-0000-1000-8000-00805f9b34fb` |
| Secondary notify | `00000aF2-0000-1000-8000-00805f9b34fb` |
| Extra/encryption | `00000aF8-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| Battery service *(generic; not relied on — see correction)* | `0000180F-0000-1000-8000-00805f9b34fb` |
| Battery characteristic *(generic; not relied on)* | `00002A19-0000-1000-8000-00805f9b34fb` |

## VeryFit Architecture (from APK reverse engineering)

```
Java/Kotlin glue layer
  → com.veryfit.multi.nativeprotocol.Protocol (JNI)
    → libVeryFitMulti.so / libprotocol.so   ← builds/parses the BINARY IDO frames
      → BLE GATT writes/reads (binary on the wire)
```

The crossing point matters for a clean-room reimplementation:
`Protocol.WriteJsonData(json, type)` takes JSON **into** native and the lib emits the
binary frame written to `0x0AF6`; `Protocol.ReceiveDatafromBle(byte[])` takes the
**raw binary** notification and the lib returns JSON via `CallBackJsonData(bytes,
type, status)`. So **JSON is an internal API detail, never the BLE payload** — an
independent bridge must replicate the binary framing, not send JSON.

Native sync entry points:
- `StartSyncHealthData()` / `StopSyncHealthData()`
- `startSyncActivityData()` / `stopSyncActivityData()`
- `startSyncGpsData()` / `stopSyncGpsData()`
- `StartSyncConfigInfo()` / `StopSyncConfigInfo()`
- `SetSyncHealthOffset(type, offset)`

V3 health data type IDs (native):
| Type | Meaning |
|-----:|---------|
| 1 | SpO2 |
| 2 | Pressure/stress |
| 3 | Heart rate |
| 5 | GPS |
| 6 | Swim |
| 7 | Sleep |
| 8 | Sport/workout |
| 9 | Noise |
| 10 | Temperature |
| 11 | One-minute sport |
| 12 | Blood pressure |
| 14 | Respiratory rate |
| 15 | Body power |
| 16 | HRV |
| 17 | Drink plan |
| 18 | Body composition |
| 19 | ECG |
| 20 | Multi-activity |

## Phase 1: BLE Proof of Concept ✅

**Status:** ✅ Done and **verified on hardware** (2026-06-22), uncommitted

**Objective:** Connect to watch, perform basic handshake, read battery, log raw events.

**Deliverables:**
- Scan for and connect to Active 4 Pro ✅ (matched by static MAC `F4:91:29:51:C6:45`)
- Discover service `0x0AF0` ✅
- Request MTU 517, expect negotiated 247 ✅
- Enable notifications on `0x0AF7` and `0x0AF2` ✅
- Write `02 04` to `0x0AF6`, confirm MAC response ✅ **watch-verified** — the 2-byte
  command works; response `02 04 <mac×2>` → MAC bytes[2..7]. (The `AB`-header binary
  IDO frame builder is retained for the still-unverified long `0x33` sync frames.)
- ~~Read battery via standard GATT `0x180F`~~ → Battery from `02 01` (byte[7]) and
  `02 A7` (byte[5]) notification responses ✅ **watch-verified** (two samples)
- Log raw protocol events (developer-only ring buffer + logcat mirror, tag `WatchBLE`) ✅
- Display connection status and telemetry in new "Watch" tab ✅

**Files:** See `docs/plans/ble-phase-1-probe.md` for detailed architecture + the live
verification log. See `docs/plans/ble-protocol-reverse-engineering-plan.md` for the
scalable VeryFit-as-oracle plan; avoid expanding the one-capture/one-parser workflow
for every metric.

**Verification:** ✅ all done — see the verification table in `ble-phase-1-probe.md`.
1. `./gradlew testDebugUnitTest` ✅
2. `./gradlew assembleDebug` ✅
3. Installed on Samsung Galaxy S21 (Android 14), connected to Active 4 Pro ✅
4. scan → connect → MTU 247 → notifications → write → RX (battery/MAC) all confirmed ✅
   Battery/MAC display is **best-effort until a capture confirms the framing** — a
   blank battery field is expected, not a defect, until then.

## Phase 2: Safe Dashboard Metrics

**Status:** Android side implemented. Dashboard side (API endpoint, schema,
Settings UI) is tracked in the dashboard repo and out of scope here.

**Objective:** Upload connection/device telemetry to dashboard. No health metrics yet.

**Android implementation (this repo):**
- `WatchSyncRequest`/`WatchSyncEvent`/`WatchSyncResult` domain models +
  `WatchSyncDto`/`WatchRawEventDto`/`WatchSyncResponseDto` wire DTOs matching the
  request body below.
- `WatchSyncMapper` builds the request from the live `WatchConnectionState`
  (MAC-preferred device id, lowercase connection-state, ISO-8601 timestamps,
  developer-only raw handshake events).
- `WatchPacketLogger` now captures structured `RawEvent`s (TX in
  `WatchBleManager.writeCommand`, RX in `WatchGattCallback.onCharacteristicChanged`).
- `DashboardApiClient.syncWatch` → `POST /api/widget/v1/watch/sync`, wired through
  the Retrofit + Fake clients and `DashboardRepository`.
- `WatchSyncWorker` uploads the current telemetry; `WatchSyncScheduler` fires it
  one-off on every connection event (via `WatchBleManager.onConnectionEvent`) and
  on a 6-hour periodic cadence. A manual "Sync to Dashboard" button on the Watch
  screen triggers it too.
- Degrades quietly when unconfigured or with no identifiable device; retries on
  transient/5xx failures, treats auth failures as terminal.

**Dashboard additions:**

### API Endpoint: `POST /api/widget/v1/watch/sync`

Request:
```json
{
  "deviceId": "f4:91:29:51:c6:45",
  "connectedAt": "2026-06-21T02:30:00Z",
  "disconnectedAt": null,
  "batteryPercent": 78,
  "mtu": 247,
  "connectionState": "connected",
  "protocolVersion": null,
  "rawEvents": [
    {
      "direction": "phone->watch",
      "characteristic": "0x0AF6",
      "commandFamily": "0x02",
      "hex": "0204",
      "timestamp": "2026-06-21T02:30:01Z"
    },
    {
      "direction": "watch->phone",
      "characteristic": "0x0AF7",
      "commandFamily": "0x02",
      "hex": "0204f4912951c645f4912951c645",
      "timestamp": "2026-06-21T02:30:01Z"
    }
  ]
}
```

### Dashboard Schema

New tables:

```sql
-- Device registry
CREATE TABLE watch_devices (
  id TEXT PRIMARY KEY,           -- MAC address
  user_id INTEGER NOT NULL,
  name TEXT,
  model TEXT,
  protocol_service_uuid TEXT,
  created_at TEXT,
  FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Connection history
CREATE TABLE watch_connections (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  connected_at TEXT,
  disconnected_at TEXT,
  battery_percent INTEGER,
  mtu INTEGER,
  connection_state TEXT,         -- connected | disconnected | error
  error_reason TEXT,
  created_at TEXT
);

-- Raw protocol events (developer-only, TTL 24h)
CREATE TABLE watch_raw_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  connection_id INTEGER,
  direction TEXT,                -- phone->watch | watch->phone
  characteristic_uuid TEXT,
  command_family TEXT,
  command_id TEXT,
  payload_hex TEXT,
  checksum_valid BOOLEAN,
  timestamp TEXT,
  ttl_expires_at TEXT            -- auto-delete after 24h
);
```

### Dashboard UI

- Settings → Devices → Add "Watch" device type
- Watch device card: name, last seen, battery, connection state
- Developer-only raw event viewer (hidden by default)

**Android additions:** ✅ done
- `WatchSyncWorker` auto-uploads connection state changes (via `WatchSyncScheduler`
  + `WatchBleManager.onConnectionEvent`)
- `WatchSyncDto` re-shaped to the request body above; request/response domain
  models and mapper added

## Phase 3: Health Schema Support

**Objective:** Add schema support for APK-confirmed health metrics, marked unverified until decoded from watch.

> **Capability-gate before assuming support.** The table below is the APK's *model
> catalogue* (what the SDK can represent across all IDO devices), **not** what the
> Active 4 Pro returns. The native lib exposes `support_*` feature flags and a device
> **function table** (e.g. `support_sync_body_composition`, `support_sync_ecg`,
> `V3_support_scientific_sleep`, `support_emotion_health`). Discover the function
> table on connect and gate each metric on it; do not build UI/sync for a metric until
> the device reports it. "Confidence" below is APK-schema confidence, not device
> confidence.

| Metric | APK Model Class | Confidence |
|--------|-----------------|------------|
| Steps | `HealthActivity.step` | High |
| Calories | `HealthActivity.calories` | High |
| Distance | `HealthActivity.distance` | High |
| Active duration | `HealthActivity.durations` | High |
| Heart rate samples | `HealthHeartRate` | High |
| Resting/silent HR | `HealthHeartRate.silentHeart` | High |
| HR zones | `HealthActivity.range1..range5` | High |
| Sleep summary | `HealthSleep` / `HealthSleepV3` | High |
| Sleep stages | `HealthSleepV3` (deep/light/REM/wake) | High |
| Sleep score | `HealthSleep.sleepScore` | High |
| SpO2 | `HealthSpO2` | Medium (feature-gated) |
| Stress/pressure | `HealthPressure` | Medium (feature-gated) |
| HRV | `HealthHRVdata` | Medium (feature-gated) |
| Body power | `HealthBodyPower` | Medium (feature-gated) |
| Respiratory rate | `HealthRespiratoryRate` | Medium (feature-gated) |
| Workouts | `HealthSport` / `HealthActivityV3` | High |
| GPS tracks | `HealthGpsV3` | Medium |
| Blood pressure | `HealthBloodPressed` | Low (device-dependent) |
| Temperature | `HealthTemperature` | Low (device-dependent) |
| Noise | `HealthNoise` | Low (device-dependent) |

**Dashboard schema additions:**

```sql
CREATE TABLE watch_health_syncs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  sync_type TEXT,                -- activity | heart_rate | sleep | sport | spo2 | pressure | etc.
  sync_started_at TEXT,
  sync_completed_at TEXT,
  records_count INTEGER,
  status TEXT,                   -- pending | success | partial | error
  error_message TEXT
);

CREATE TABLE watch_health_metrics (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  metric_type TEXT NOT NULL,     -- steps | heart_rate | sleep | spo2 | stress | etc.
  recorded_at TEXT NOT NULL,
  value REAL,
  unit TEXT,
  confidence TEXT,               -- verified | unverified | estimated
  source_command TEXT,
  raw_event_id INTEGER,
  FOREIGN KEY (raw_event_id) REFERENCES watch_raw_events(id)
);
```

**Android additions:**
- `WatchHealthDecoder` — placeholder interfaces for each metric type
- `WatchHealthRepository` — persists decoded metrics to local Room cache
- Upload decoded metrics to dashboard via `POST /api/widget/v1/watch/health`

## Phase 4: Command Decoding

**Objective:** Decode the `0x33` and `0xD1` packet families, implement reassembly.

> **Status of this structure: capture-derived, not re-verifiable in this repo.** The
> byte layout below came from the Bluetooth capture session `20260621_010233_b4f7a6`,
> whose raw artifacts are **not present here**. It is *consistent with* the native
> strings (`head fixed`, `cmd`, `key`, `nseq`, `version`, `length`, CRC16) and the CRC
> matches native `crc16_compute`, so it is a credible hypothesis — but the field
> meanings (which bytes are cmd vs key vs seq, endianness, and which commands carry
> battery/device-info) are **unconfirmed**. Re-capture and diff against
> `WatchProtocol.parseFrame` output before implementing decode logic. Note also: this
> `0x33` head differs from the placeholder head byte in `WatchProtocol.kt` (`0xAB`) —
> reconciling the two is exactly what the capture must settle.

**0x33 packet structure (from capture analysis — UNVERIFIED here):**

```
33 DA AD DA AD 01 LL LL CC CC SS SS [payload...] CRC CRC
```

| Field | Meaning |
|-------|---------|
| `33` | Protocol marker |
| `DA AD DA AD` | Magic header |
| `01` | Version |
| `LL LL` | Little-endian length (total - 3) |
| `CC CC` | Little-endian command ID |
| `SS SS` | Little-endian sequence/index |
| `payload` | Command data |
| `CRC CRC` | CRC-16/CCITT-FALSE, little-endian |

**CRC-16/CCITT-FALSE** (algorithm matches native `crc16_compute`; that the `0x33`
frame is guarded by *this* variant rather than the `0x8005`/ARC `crc16_x16x15x21`
the lib also contains is **capture-gated**):

```python
def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc
```

**Implementation:**
- `WatchPacketReassembler` — buffers fragmented ATT packets, reassembles `0x33` messages
- `WatchCommandDecoder` — maps command IDs to metric types
- `WatchCrcValidator` — validates CRC on reassembled packets

**D1 file/bulk transfer protocol:**
- `D1 01` — filename/header
- `D1 05` — acknowledge
- `D1 02` — data chunk
- `D1 03` — finish/checksum
- Used for: contacts, EPO.DAT (GPS assistance), EPO_GR_3_1.DAT, EPO_GAL_3.DAT, EPO_BDS_3.DAT

**Android additions:**
- `WatchFileTransferManager` — handles D1 protocol for GPS/EPO sync
- `WatchEpoSyncWorker` — periodic EPO data refresh (if GPS is used)

## Phase 5: Health Sync Implementation

**Objective:** Implement actual health data sync from watch to dashboard.

**Approach:** Clean-room reverse-engineering of sync commands.

### Method 1: Focused Capture Analysis

1. Perform one action at a time in official VeryFit app
2. Record Bluetooth capture for each action
3. Label capture with action: "open heart rate", "sync sleep", "start workout"
4. Compare packet deltas to identify command IDs and payload structures
5. Build command → metric mapping

### Method 2: Native Library Analysis (if licensing permits)

1. Extract `libVeryFitMulti.so` strings and symbols
2. Identify sync command generators
3. Map Java callback IDs to native type numbers
4. Rebuild command sequences in Kotlin

### Method 3: Hybrid (recommended)

1. Use focused captures to identify command families
2. Use native library strings to confirm type mappings
3. Implement clean-room in Kotlin without bundling proprietary code

**Sync flow:**

```
App requests sync (manual or scheduled)
  → Write sync start command to 0x0AF6
  → Watch begins sending notifications on 0x0AF7
  → Reassemble 0x33 packets
  → Validate CRC
  → Decode payload into typed metrics
  → Persist to local cache
  → Upload to dashboard API
  → Write sync stop/ack command
```

**Android additions:**
- `WatchSyncManager` — orchestrates sync sessions
- `WatchHealthSyncWorker` — periodic background sync (with foreground service)
- `WatchHealthDecoder` implementations for each metric type
- `WatchHealthUploader` — batch upload to dashboard

## Phase 6: Dashboard Health UI

**Objective:** Display watch health data in dashboard.

**Widgets/cards:**
- Today: steps, heart rate, sleep score (if available)
- Health page: full metrics history, trends
- Workouts: activity sessions, GPS maps (if available)
- Sleep: sleep stages, trends, score
- Heart: HR zones, resting HR trends, HRV (if available)

**Privacy controls:**
- User can disable specific metric types
- User can delete historical data
- Raw events auto-expire (24h TTL)
- Health data is user-local, not shared with third parties

## Phase 7: Background Sync & Durability

**Objective:** Reliable background sync without user intervention.

**Android components:**
- `WatchForegroundService` — keeps BLE connection alive for sync
- `WatchSyncWorker` — periodic sync (every 15 min during day, configurable)
- `WatchBatteryOptimizationHelper` — guides user to disable battery optimization
- `WatchBootReceiver` — restart sync on device reboot
- `WatchConnectionMonitor` — auto-reconnect on disconnect

**Dashboard components:**
- Webhook/push when new data available (optional)
- Sync status indicator in Today widget
- Last sync timestamp, next scheduled sync

## Phase 8: Advanced Features

**Objective:** Full feature parity with official app where desired.

**Potential features** (each is a confirmed native IDO command module in
`libVeryFitMulti.so` — see source paths below — but all funnel through the same
`WriteJsonData`→binary-frame path, so each still needs its **frame captured** and is
**capability-gated** by the function table):
- Watch notifications — `protocol_v3_mod/message/protocol_v3_notice_message*.c`,
  `ProtocolSetNoticeEvt` / `ProtocolSetCallEvt` / `ProtocolMissedCallEvt`
- Watch settings sync (time, units, goals, alarms) — `alarm/protocol_v3_set_alarm.c`,
  config sync via `StartSyncConfigInfo`
- Weather sync — `weather/protocol_set_v3_weather.c`, `protocol_set_long_city_name.c`
- Contact sync (with privacy controls) — `other/protocol_sync_contact.c`
- Music control — `music/protocol_v3_operate_ble_music.c`, `protocol_v3_music_control.c`
- Find my watch / find my phone
- Firmware updates (OTA/DFU) — Java DFU services (`SERVICE_UUID_M6_DFU`,
  `DEVICE_DFU_*`); routes off the main `0x0AF0` path
- Custom watch faces — `getSifliDialSize` / `mkSifliDial`, watch-dial encode modules

**Note:** "native module exists" ≠ "implementable from static analysis." Each feature
still requires a focused capture to learn its frame, plus a function-table check before
exposing it. Prioritize based on user need. Do **not** assume raw command IDs.

## Privacy & Security Throughout

1. **Raw BLE data:** Developer-only, never persisted beyond 24h, redacted in production
2. **Health data:** User-owned, local-first, encrypted at rest
3. **Contacts:** Never synced without explicit consent, never stored server-side
4. **GPS:** Location data only if user opts in
5. **Third parties:** No data shared with VeryFit/IDO/cloud services
6. **Dashboard access:** Per-device tokens, scoped to read/watch health only

## Verification Checklist by Phase

### Phase 1 — ✅ COMPLETE & verified on hardware (2026-06-22)
- [x] `./gradlew testDebugUnitTest` passes
- [x] `./gradlew assembleDebug` builds
- [x] Scan finds Active 4 Pro *(matched by static MAC `F4:91:29:51:C6:45`; it advertises with no name)*
- [x] Connect succeeds *(note: toggle Bluetooth if `status=133` — dual-mode Classic/LE contention)*
- [x] Notifications enabled on `0x0AF7`/`0x0AF2`
- [x] Raw packet log visible, with per-notification frame breakdown *(also mirrored to logcat tag `WatchBLE`)*
- [x] **Capture taken** of the VeryFit app (`btsnoop_hci.log`) and analysed with `tshark`
- [x] Battery decodes from the IDO protocol *(`02 01` byte[7] / `02 A7` byte[5]; two samples confirm offsets — not via `0x180F`)*
- [x] MAC/device-info decodes *(`02 04` → MAC bytes[2..7], watch-verified — the 2-byte command works)*

### Phase 2
- [x] Android: telemetry DTO/mapper + `syncWatch` API path (unit-tested)
- [x] Android: `WatchSyncWorker` uploads on connection events + periodically
- [ ] Dashboard API accepts watch sync *(dashboard repo)*
- [ ] Device appears in dashboard Settings *(dashboard repo)*
- [ ] Connection history logged *(dashboard repo)*
- [ ] Raw events viewable (developer mode) *(dashboard repo)*

### Phase 3
- [ ] Health schema migrations run
- [ ] Metric types appear in dashboard
- [ ] All marked "unverified" initially

### Phase 4
- [ ] 0x33 packet reassembly works
- [ ] CRC validation passes
- [ ] D1 file transfer handles EPO

### Phase 5
- [ ] Steps sync from watch
- [ ] Heart rate sync from watch
- [ ] Sleep sync from watch
- [ ] Data appears in dashboard UI

### Phase 6
- [ ] Health widgets display data
- [ ] Trends/history pages work
- [ ] Privacy controls functional

### Phase 7
- [ ] Background sync runs reliably
- [ ] Auto-reconnect works
- [ ] Boot restart works
- [ ] Battery optimization handled

### Phase 8
- [ ] Notifications to watch
- [ ] Settings sync
- [ ] Weather sync
- [ ] Feature parity assessment

## Open Decisions

1. **Native library usage:** Clean-room only, or bundle extracted IDO library? (Recommended: clean-room)
2. **Background sync frequency:** Every 15 min? Hourly? User-configurable?
3. **Health data retention:** 30 days? 90 days? Unlimited? User-configurable?
4. **GPS track storage:** Full resolution or simplified? Storage budget?
5. **Firmware updates:** Support OTA, or require official app for updates?

## References

- PKS investigation note: `engineering/reviews/active-4-pro-veryfit-ble-protocol-investigation-initial-apk-findings.md`
- Phase 1 detailed plan: `docs/plans/ble-phase-1-probe.md`
- Phone app improvements: `docs/plans/phone-app-improvements.md`
- APK: `/home/apolytus/workspace/veryfit-3-4-0.apk`
- Bluetooth capture analysis: a fresh `btsnoop_hci.log` (captured 2026-06-22 from the
  official VeryFit app, analysed with `tshark`) **is** available and locked the
  watch-verified `0x02`-family commands (`02 04`/`02 01`/`02 A7`) now in
  `WatchProtocol.kt`. The older session `20260621_010233_b4f7a6` artifacts were not
  present; this newer capture supersedes that gap. Still outstanding: the `0x33`/`0xD1`
  long-frame internals, which need Stage 2 instrumentation (see reverse-engineering plan).
