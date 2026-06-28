# Private Phase 3 — activity summary decode (kickoff prompt)

> ## ⚠ Superseded 2026-06-28 — kept as reference only (read [ADR 0001](../adr/0001-vendor-ido-sdk.md))
>
> This clean-room kickoff is **not the active path**. The private build now decodes activity (and
> every other health metric) through the **vendored IDO/VeryFit SDK** behind `IdoSdkWatchEngine`
> (ADR 0001), which delivers already-decoded `HealthSportV3` / `HealthActivityV3` records — see the
> [metric support matrix](../watch-metric-support-matrix.md) (activity day is `SHOWN_IN_UI`). The
> clean-room code this prompt builds on (`ble/WatchActivityReassembler.kt`, `WatchGattCallback`,
> `WatchBleManager`, `WatchProtocol`) has been **removed from the tree** along with the old `ble/`
> package; the `33 DA AD` buffer-offset decode is therefore obsolete for the private build. This doc
> is retained only as the **clean-room-later playbook** should a future clean-room `WatchEngine` be
> reintroduced behind the interface (ADR 0001 reserves that option). Do not start Phase 3 as written.

> Paste this into a fresh Claude Code session in `dashboard-android` to start Phase 3.
> It is self-contained; read the linked files before changing code.

## Goal

Decode **only the summary/header fields** from a reassembled `33 DA AD` activity
buffer into a typed result, behind a capture gate, with explicit per-field confidence
labels. **No per-point HR/cadence/pace arrays yet, no TCX/export, no "mark synced".**
This is the first step that turns raw bytes into useful private-dashboard data.

## Where Phase 2 left off (already done, committed `35e8432`)

- `ble/WatchActivityReassembler.kt` reassembles `33 DA AD` chunks into a full buffer
  (reference semantics: append each `0x33` chunk from offset 1; total length = LE uint16
  at preamble bytes 6..7; activity version at full-buffer offset
  `WatchProtocol.ACTIVITY_DATA_OFFSET` = 25). Unsupported version is non-fatal.
- `WatchGattCallback` routes `0x33` notifications through it and logs the version.
- Manual **"Activity Sync (33 DA AD · UNVERIFIED)"** button →
  `WatchBleManager.requestActivitySync()` → writes
  `33 DA AD DA AD 01 10 00 04 00 0B 01 00 04 00 00 00 00 00` (UNVERIFIED).
- Debug scan is **MAC-locked** to `KNOWN_WATCH_MAC` (`F4:91:29:51:C6:45`) because a
  neighbour's identical Active 4 Pro (`F4:EC:E2:C9:E1:82`, deviceId 7896 is a model
  code, not a serial) is in range. Do not loosen this.
- `WatchProtocol` has `activityVersion()` / `isSupportedActivityVersion()` (only **16**)
  / `describeActivityVersion()`. Activity version is NOT `WatchBasicInfo.firmwareVersion`.

## ⚠ Blocker to resolve FIRST — get a real version-16 buffer

On-device the watch returned a **125-byte buffer with activity version 0 (empty / no
stored activity)** — not version 16. The Phase 2 reference decoder only supports version
16, and there is nothing to decode in a v0 buffer. Before writing decode logic, obtain a
**non-empty version-16 buffer**, via one of:

1. Record a real workout on the watch (or let it accumulate daily activity), then trigger
   Activity Sync and capture the bytes from logcat tag `WatchBLE` (`ACTIVITY` / `RX` lines).
2. Try the reference request **variant** `33 DA AD DA AD 01 10 00 04 00 16 00 00 04 …`
   (sequence bytes are reference-noted as variable) and/or a different offset, watching
   for a version-16 buffer. Add it as another manual-only button — never auto-fire.
3. If no real v16 buffer is obtainable, decode against the **reference simulator bytes**
   only and clearly label the decoder UNVERIFIED-on-hardware until a real capture lands.

Capture and commit a redacted sample buffer (debug-only) so the decoder has a fixture.

## Decode targets (summary only)

All offsets are **relative to `data_offset = 25`** of the reassembled buffer (so absolute
= 25 + field offset), per the IDO/VeryFit/Ryze reference. Decode, with a confidence label
each (`watch-verified` / `capture-observed` / `reference-only`):

- activity version (already at offset 25+0)
- start time
- activity/workout type
- duration
- steps
- distance
- calories
- average / max / min heart rate (when present)

Keep per-point arrays (HR/cadence/pace/GPS) for a later phase. Malformed/short buffers
must fail safely (return null / partial with labels), never crash the BLE flow.

## Suggested implementation

- New `ble/WatchActivitySummary.kt` (data class) + `WatchProtocol.parseActivitySummary(buffer)`
  in the protocol layer (pure, unit-testable), mirroring the `parseBasicInfo` style:
  strict header checks, length-safe optional trailing fields, no throws.
- Feed it from `WatchGattCallback`'s `Complete` branch (currently version-only logging):
  parse + log the summary when version is supported; keep unsupported non-fatal.
- Do NOT upload or persist decoded activity by default (privacy). Keep raw buffers
  debug-gated/redacted. Surface the summary in the Watch screen's log/Device Info only.

## Tests & verification

- JVM unit tests (`WatchActivityProtocolTest` or extend `WatchActivityReassemblerTest`):
  decode the reference simulator bytes + any real captured v16 buffer; assert each field;
  assert malformed/short buffers fail safely; assert confidence labels.
- `./gradlew testDebugUnitTest assembleDebug` must pass.
- On-device (env in memory `[[dev-environment]]`): deploy fresh APK (uninstall first —
  signature mismatch), grant `BLUETOOTH_SCAN/CONNECT` + `POST_NOTIFICATIONS` via
  `pm grant --user 0`, force-stop VeryFit, launch `--es start_route watch`, scan, trigger
  Activity Sync, confirm a decoded summary roughly matches the watch/VeryFit values.

## Constraints (from `dashboard-android/CLAUDE.md`)

- Confidence label every protocol claim; "APK model exists" != "watch supports it".
- Raw health/activity payloads: developer-only, redacted, not model-facing by default.
- Clean-room only; no copying VeryFit/native code.
- Do not mark activities synced on the watch.
- Do not commit/push unless Fabian asks.

## Acceptance

A real (or reference) version-16 activity buffer decodes into a summary whose
start/type/duration/steps/distance/calories/HR roughly match the watch, each field
carries a confidence label, malformed buffers fail safely, BLE stays connected, and
nothing is marked synced or uploaded by default.
