# Active 4 Pro BLE Protocol Reverse-Engineering Plan

## Purpose

Avoid a slow, one-field-at-a-time Bluetooth workflow for the Kogan Active 4 Pro / VeryFit watch integration.

The production goal remains an independent dashboard Android bridge:

```text
Active 4 Pro watch → dashboard-android app → self-hosted dashboard API
```

VeryFit should be used as a temporary protocol oracle during reverse engineering, not as a runtime dependency of the final app.

## Current state

The Android app can already perform the transport-level work directly:

1. Scan/connect to the watch.
2. Discover service `00000af0-0000-1000-8000-00805f9b34fb`.
3. Enable notifications on `0AF7` and `0AF2`.
4. Write to `0AF6` through a serialized GATT queue.
5. Receive/log raw notifications.
6. Upload connection/device telemetry to the dashboard.

A focused official-app Bluetooth capture revealed the first useful command/response pair:

```text
WRITE 0AF6: 02 01
NOTIFY 0AF7: 33 DA AD DA AD 01 1D 00 ...
NOTIFY 0AF7: 02 01 D8 1E 01 01 00 5F 01 00 01 00 5A 02 02 03 06 00
```

The app currently uses `02 01` as a captured status probe and treats byte 7 of the `02 01 ...` response as a capture-derived battery percentage. This is a useful foothold, but it is not a scalable protocol strategy.

## Why the capture-only workflow does not scale

Repeating this loop for every metric would take too long:

1. Trigger one VeryFit action.
2. Export btsnoop.
3. Manually find writes/notifications.
4. Guess byte offsets.
5. Patch one parser.
6. Repeat for battery, steps, sleep, HR, SpO2, stress, workouts, etc.

This also risks building brittle parsers from too few samples. For example, `0x5F` is plausibly 95% battery in the current sample, but that does not yet prove the full status payload layout.

## Key protocol fact

The APK contains high-level protocol information, but the BLE wire protocol is mostly behind native code.

Confirmed architecture:

```text
VeryFit Java/Kotlin layer
  → com.veryfit.multi.nativeprotocol.Protocol JNI boundary
    → libVeryFitMulti.so / libprotocol.so
      → binary BLE frames written to 0AF6
      ← raw binary notifications from 0AF7/0AF2
  ← decoded JSON callbacks to Java/Kotlin
```

Important boundary methods / symbols to instrument:

- `Protocol.WriteJsonData(byte[] json, int type)`
- `Protocol.ReceiveDatafromBle(byte[] frame)`
- `CallBackWriteDataToBle(...)`
- `CallBackJsonData(...)`
- `StartSyncHealthData()` / `StopSyncHealthData()`
- `StartSyncConfigInfo()` / `StopSyncConfigInfo()`
- `startSyncActivityData()` / `stopSyncActivityData()`
- `SetSyncHealthOffset(type, offset)`

JSON exists at the Java↔native boundary. BLE payloads are binary frames, not JSON.

## Strategy

### Stage 1 — Stabilize direct BLE transport

Status: mostly done.

Keep dashboard Android independent and direct-to-watch:

- broad BLE scan with device-name/service matching
- GATT connect with bond handling
- MTU negotiation
- serial CCCD enable for notifiable characteristics only
- serialized writes to `0AF6`
- raw event logging and copy-to-clipboard
- raw hex probe input
- captured `02 01` status probe retry burst

This stage proves the app can talk to the watch without VeryFit installed or running.

### Stage 2 — Instrument VeryFit as a protocol oracle

Use VeryFit only during reverse engineering.

Preferred method: Frida instrumentation of the official app.

Goal: log the Java/native boundary and BLE write callback so each official action yields a structured mapping:

```text
VeryFit user action / sync step
→ native API call name
→ type id
→ input JSON, if present
→ exact BLE bytes written to 0AF6
→ raw notification bytes from 0AF7/0AF2
→ decoded JSON callback from native
```

This is the scalable replacement for manual btsnoop interpretation. It tells us both the command bytes and the decoded meaning of responses.

Minimum hooks:

1. Java/native protocol entry:
   - method name
   - `type` argument
   - JSON/request bytes
2. BLE write callback:
   - characteristic UUID
   - byte array written
3. BLE receive path:
   - characteristic UUID
   - raw notification bytes passed into native
4. decoded JSON callback:
   - callback type/status
   - decoded JSON bytes/string

Useful capture sessions:

1. Fresh connect + initial sync.
2. Battery/status screen.
3. Device info / about screen.
4. Function table / feature capability fetch, if exposed during connect.
5. Daily activity / steps sync.
6. Sleep sync.
7. Heart-rate sync.
8. SpO2 sync, if supported.
9. Stress/pressure sync, if supported.
10. Workout/activity sync, if supported.

### Stage 3 — Build a command catalog

From the instrumented traces, create a small catalog for dashboard Android.

Each catalog entry should include:

- name, e.g. `status_probe`, `device_info`, `function_table`, `daily_activity_sync`
- VeryFit/native type id, if known
- BLE write bytes or frame-builder inputs
- required preconditions
- expected notification sequence
- decoded JSON shape from VeryFit
- dashboard fields populated
- confidence level
- sample count

Example shape:

```text
Command: status_probe
Write: 02 01
Notify: 33 DA AD ... then 02 01 ...
Decoded meaning: battery/status block
Confidence: medium; needs more samples
Dashboard fields: batteryPercent
```

Do not graduate a command to production parsing until it has either:

- multiple samples across reconnects/battery levels, or
- native decoded JSON proving the field mapping.

### Stage 4 — Implement independent parsers/builders

Reimplement the subset of the protocol the dashboard needs.

Prioritize:

1. stable connection/device identity
2. battery/status
3. function table/capability flags
4. daily activity summary: steps/calories/distance
5. sleep summary
6. heart-rate summary/history
7. SpO2/stress only if the function table says this watch supports them
8. workout/activity sessions

Rules:

- Capability-gate every metric using the function table / support flags.
- Keep raw packet logging available while a command is experimental.
- Label capture-derived parsers clearly until confirmed.
- Prefer decoded VeryFit JSON as the ground truth for response semantics.
- Do not assume every IDO SDK model exists on this specific watch.

### Stage 5 — Consider native-library reuse only as a research shortcut

A possible shortcut is to reuse `libVeryFitMulti.so` / `libprotocol.so` directly from dashboard Android:

```text
dashboard JSON request → vendor native encoder → BLE bytes
dashboad receives BLE bytes → vendor native decoder → JSON callback
```

This could provide broad protocol coverage quickly, but it is not the preferred production path.

Risks:

- licensing / redistribution concerns
- package/class-name assumptions
- JNI callback shape assumptions
- initialization and device database dependencies
- possible session keys or pairing state hidden in VeryFit storage
- brittle across app versions

Use this only to learn the protocol or as a last-resort prototype. The production target should remain a clean independent implementation.

## Independence model

Final desired state:

```text
VeryFit installed temporarily: yes, for protocol discovery only
VeryFit running during dashboard use: no
VeryFit installed for production dashboard Android: no
Vendor native libraries bundled in production: preferably no
```

Dashboard Android should own:

- BLE connection lifecycle
- command scheduling
- response parsing
- dashboard upload
- retry/backoff behavior
- privacy boundaries

VeryFit should only provide evidence while reverse-engineering.

## Deliverables

### Reverse-engineering deliverables

- Frida hook script(s) for VeryFit protocol boundary.
- Structured trace logs for the useful capture sessions.
- Command catalog markdown/JSON with confidence labels.
- Sample raw BLE frames paired with decoded native JSON.

### Android implementation deliverables

- Stable command scheduler for watch sync.
- Confirmed command builders or literal command templates.
- Parsers backed by decoded JSON/capture samples.
- Capability-gated metric model.
- Dashboard sync payloads for confirmed metrics.
- Developer diagnostics kept behind the Watch screen.

## Success criteria

This plan succeeds when adding a new watch metric is no longer a manual byte-guessing exercise.

For a new metric, the workflow should become:

1. Trigger metric in VeryFit once or twice with instrumentation enabled.
2. Capture request bytes + decoded JSON.
3. Add a catalog entry.
4. Implement or extend a parser with tests.
5. Verify against one live dashboard Android run.

Target: hours per metric category, not days/weeks per individual byte field.

## Immediate next step

Set up Frida-based instrumentation against VeryFit and capture the protocol boundary for:

1. fresh connection/status sync
2. battery/status
3. device info
4. function table/capabilities
5. daily activity/steps

Those five traces should be enough to replace the current fragile battery heuristic with a confirmed status parser and establish the pattern for broader health data.
