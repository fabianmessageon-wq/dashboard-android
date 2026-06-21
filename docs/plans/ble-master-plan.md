# Active 4 Pro BLE Integration — Master Plan

## Goal

Build an independent Android-dashboard bridge for the Kogan Active 4 Pro smartwatch, avoiding VeryFit/vendor/cloud/third-party health platforms. The watch data flows: Active 4 Pro → dashboard-android app → self-hosted dashboard API.

## Context

- **Watch:** Kogan Active 4 Pro (rebranded IDO/VeryFit device)
- **Official app:** VeryFit (`com.watch.life` v3.4.0), uses IDO BLE SDK with native protocol library (`libVeryFitMulti.so`)
- **Dashboard Android app:** Native Kotlin + Jetpack Compose, minSdk 26, compileSdk 34
- **Protocol:** BLE GATT, service `0x0AF0`, custom binary protocol with command families `0x02`, `0x03`, `0x33`, `0xD1`
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
| Battery service | `0000180F-0000-1000-8000-00805f9b34fb` |
| Battery characteristic | `00002A19-0000-1000-8000-00805f9b34fb` |

## VeryFit Architecture (from APK reverse engineering)

```
Java/Kotlin glue layer
  → com.veryfit.multi.nativeprotocol.Protocol (JNI)
    → libVeryFitMulti.so / libprotocol.so
      → BLE GATT writes/reads
```

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

**Status:** Implemented (uncommitted)

**Objective:** Connect to watch, perform basic handshake, read battery, log raw events.

**Deliverables:**
- Scan for and connect to Active 4 Pro
- Discover service `0x0AF0`
- Request MTU 517, expect negotiated 247
- Enable notifications on `0x0AF7` and `0x0AF2`
- Write `02:04` command to `0x0AF6`, confirm MAC response
- Read battery via standard GATT `0x180F`
- Log raw protocol events (developer-only in-memory ring buffer)
- Display connection status and telemetry in new "Watch" tab

**Files:** See `docs/plans/ble-phase-1-probe.md` for detailed architecture.

**Verification:**
1. `./gradlew testDebugUnitTest`
2. `./gradlew assembleDebug`
3. Install on Samsung S21, pair with Active 4 Pro
4. Confirm scan → connect → battery read → 02:04 MAC response

## Phase 2: Safe Dashboard Metrics

**Objective:** Upload connection/device telemetry to dashboard. No health metrics yet.

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

**Android additions:**
- `WatchSyncWorker` auto-uploads connection state changes
- `WatchSyncDto` already created in Phase 1

## Phase 3: Health Schema Support

**Objective:** Add schema support for APK-confirmed health metrics, marked unverified until decoded from watch.

**APK-confirmed metric candidates:**

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

**0x33 packet structure (from capture analysis):**

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

**CRC-16/CCITT-FALSE (verified against capture):**

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

**Potential features:**
- Watch notifications (forward phone notifications to watch)
- Watch settings sync (time, units, goals, alarms)
- Weather sync
- Contact sync (with privacy controls)
- Music control
- Find my watch / find my phone
- Firmware updates (OTA/DFU)
- Custom watch faces

**Note:** Each feature requires additional reverse engineering. Prioritize based on user need.

## Privacy & Security Throughout

1. **Raw BLE data:** Developer-only, never persisted beyond 24h, redacted in production
2. **Health data:** User-owned, local-first, encrypted at rest
3. **Contacts:** Never synced without explicit consent, never stored server-side
4. **GPS:** Location data only if user opts in
5. **Third parties:** No data shared with VeryFit/IDO/cloud services
6. **Dashboard access:** Per-device tokens, scoped to read/watch health only

## Verification Checklist by Phase

### Phase 1
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew assembleDebug` builds
- [ ] Scan finds Active 4 Pro
- [ ] Connect succeeds
- [ ] Battery reads correctly
- [ ] 02:04 returns MAC
- [ ] Raw packet log visible

### Phase 2
- [ ] Dashboard API accepts watch sync
- [ ] Device appears in dashboard Settings
- [ ] Connection history logged
- [ ] Raw events viewable (developer mode)

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
- Bluetooth capture analysis: session `20260621_010233_b4f7a6`
