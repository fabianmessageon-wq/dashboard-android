# Post-W7 — connect-stall reliability + intraday HR + close-outs (kickoff prompt)

> Paste this into a fresh Claude Code session in `BLE-phone-integration`. Self-contained, but read the
> linked files before acting. Created 2026-06-28 after W7 notification/call mirroring was fixed and
> hardware-verified (see [`docs/watch-private-engine-test-plan.md`](../watch-private-engine-test-plan.md)
> Lane-D results and memory `w7-notification-hardware`).

## Where things stand

The watch **health** path and **W7 notification/call mirroring** are both done and verified end-to-end
on hardware (SM-G991B / Android 14 + Active 4 Pro). W7's real bug is fixed: the watch needs the
`NotificationPara` send path (not `setV3MessageNotice`) + `MessageNotifyState=ALLOW`, and wrist
answer/reject is Bluetooth-HFP-native (our SDK call-control path is a kept-but-dead fallback). SMS +
call mirroring, boot/update re-arm, privacy, and real dashboard upload (`201`) all PASS.

This session is **correctness hardening + the last big data gap + cheap close-outs**. No large new
features. Tasks are ordered by value/risk; do them top-down, committing/handing off after each.

## Hard rules (verify before relying — see memory)

- **Deploy with `installDebug` / `adb install -r` — NEVER `adb uninstall` / `pm clear`.** Uninstall
  wipes the dashboard URL+token (EncryptedSharedPreferences) and silently degrades uploads to a fake
  "success" sink. Prove a real upload via okhttp `--> POST …/watch/health` + `<-- 201` (no okhttp
  lines = fake sink). Memory: `upload-config-gotcha`.
- **Before any sync/BLE test: `adb shell am force-stop --user 0 com.watch.life`, then start `adb
  logcat` FIRST.** The watch tolerates one BLE owner; DEBUG capture lines are the only durable copy of
  decoded payloads (upload has **no** local retry).
- **Privacy:** decoded health payloads + notification contents are developer-only — never model-facing.
  Filter logcat to app tags (`IdoSdkWatchEngine`, `WatchConnService`, `WatchSyncWorker`,
  `WatchHealthUpload`, `okhttp.OkHttpClient`); the native `DEBUG LOG` tag prints raw content on debug.
- **VPS `srv1464866`: do NOT run `db:push` or any mutating step** (active coding processes). `main`
  already carries the watch schema; the server deploys from `main`. Read-only queries only. Memory:
  `vps-no-dbpush`.
- Commit only when asked; if on `main`, branch first. (Note: the Windows test box is **not** a git
  repo — code/docs are edited locally and synced to the repo elsewhere.)

## Environment quickref (Fabian's Windows box)

- `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` before `.\gradlew.bat`.
- `adb` = `C:\Users\davo\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Phone **SM-G991B**
  (Galaxy S21, Android 14 / SDK 34) on **wireless adb** (IP seen 192.168.20.101). If `adb devices` is
  empty, `adb reconnect offline`; after a **reboot** wireless adb stays down until the phone is
  unlocked — expect to ask Fabian to toggle Wireless debugging or give the new IP:port.
- Package `dev.jaredhq.dashboardandroid`. Launch to Watch tab:
  `am start -n dev.jaredhq.dashboardandroid/.MainActivity --es start_route watch`.
- Lockscreen non-secure: `input keyevent KEYCODE_WAKEUP` then `input swipe 540 1600 540 600`.
- W7 mirroring is currently **enabled** on the phone (notification access + `ANSWER_PHONE_CALLS`
  granted this session; leave as-is unless asked). Build: `./gradlew testDebugUnitTest assembleDebug
  lintDebug` all green as of 2026-06-28.

## Primary work (ordered)

### 1. Connect-stall reliability fix (Tier 1 — protects always-on + boot re-arm)

**Bug:** `WatchConnectionService.maintain()`
([`work/WatchConnectionService.kt`](../../app/src/main/java/dev/jaredhq/dashboardandroid/work/WatchConnectionService.kt),
~lines 83–100) only retries `connect()` when `connectionState == DISCONNECTED`. The SDK can wedge at
`CONNECTING` (observed once after a **cold boot**: sat ~2 min at `onConnecting` with the Classic-BT/HFP
link up but the BLE/GATT connect never completing; recovered only on a manual relaunch). A wedged
`CONNECTING` never reaches `DISCONNECTED`, so `connectionState.first { it == DISCONNECTED }` suspends
forever and the link silently stays down until the user opens the app — defeating the always-on link +
boot re-arm.

**Fix:** add a connect watchdog. Options (pick the cleanest):
- In `maintain()`, after issuing `connect()`, if the state hasn't reached `CONNECTED` within ~30–45 s
  (it's still `SCANNING`/`CONNECTING`), call `engine.disconnect()` to force `DISCONNECTED` and let the
  loop retry with the existing 60 s backoff; or
- In `IdoSdkWatchEngine`, add a connect-timeout that, if `onConnectSuccess` hasn't fired within N s of
  `onConnectStart`/`onConnecting`, drives the state to `DISCONNECTED` (and/or `BLEManager.disconnect()`)
  so callers see a clean failure. Reuse the existing GATT-133 retry budget (`MAX_CONNECT_RETRIES`,
  `connectRetryHandler`) rather than adding a parallel one.

Verify: reboot the phone (Fabian helps restore adb), confirm the link comes up unattended within a
couple of minutes (no manual relaunch), and that a normal in-range reconnect still works. Add a JVM
test for the timeout/transition logic if a pure seam exists; otherwise document why not.

### 2. Intraday HR — `heart_rate_second` (Tier 2 — biggest remaining data gap)

The watch **delivers** second-by-second HR every sync and we **drop** it: diagnostics show
`heart_rate_second(p=1,i≈294,m=0)` and `sync delivered-but-dropped: heart_rate_second`; the engine
already logs a `heartRateSecond probe:` line (items≈294, nonZero≈140, `silentHR=54`, but
`startTime=0`, `hrData=null`). So — contrary to the old "needs a btsnoop capture" assumption — the data
is arriving through the SDK `ISyncDataListener`; the work is **mapping**, not capture.

Slice:
- Find the exact `ISyncDataListener` callback delivering the second-level items (start from the
  `heartRateSecond probe` log + `WatchSyncDiagnostics.HEART_RATE_SECOND` in
  [`IdoSdkWatchEngine.kt`](../../app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/IdoSdkWatchEngine.kt)).
  Resolve the per-item wall-clock timestamp (mirror the V3 point-metric mappers' `localDateTime` /
  range handling) — the `startTime=0`/`hrData=null` parent quirk is the thing to crack.
- Add a domain model + a `WatchHealthListener` callback + buffer it in `UploadingWatchHealthListener`,
  extend `WatchHealthUploadDto`, and add the dashboard column/table + route handling in
  `dashboard/src/app/api/widget/v1/watch/health/route.ts` (**wire-contract change** — review both
  sides, like Lane C did). Surface a count on the Watch screen. Privacy: values are developer-only.
- Verify end-to-end: decoded items in logcat → `<-- 201` → rows in the dashboard table; flip the
  [support matrix](../watch-metric-support-matrix.md) `heart_rate_second` row.

### 3. Cheap close-outs (Tier 3)

- **Sleep emission re-confirm.** Sync after a night's sleep (force-stop VeryFit first — the watch
  clears its sleep buffer after the first reader), confirm `sleep_v3(p≥1)` + a `<-- 201`, and flip the
  [support matrix](../watch-metric-support-matrix.md) Sleep row to `SHOWN_IN_UI`. (Memory
  `sleep-v3-sync-verified` says rich `rem_*/avg_*` already landed; this just closes the matrix row.)
- **Server-side cleanup (propose, don't apply).** The dashboard `/api/widget/v1/watch/sync` route is
  orphaned (its clean-room telemetry caller was deleted) and `watch_raw_events` / `watch_connections` /
  `watch_devices` are unused. Draft the drop as a proposal; **do not mutate the live VPS** (read-only).

### 4. Diligence finishers (Tier 3, optional)

- **Background `WatchSyncWorker` test.** Revoke notification access (stops the always-on service),
  force a worker run, confirm it connects + health-syncs from the background, the busy-guard prevents
  contention, and it times out cleanly when out of range. Re-grant access after.
- **Full network egress capture.** VpnService/tcpdump during connect/sync to confirm no SDK-internal
  egress to Alexa/u-blox/Airoha/starcourse/other hosts (this session's light check saw only the
  dashboard `201`). Resolves the "Open decisions" item in the test plan.

## Out of scope this session (deferred — do NOT start)

- **W7 leftovers**: DFU/firmware update + watch-face push. **W8**: clean-room engine replacement.
- **Fix B** (recover ~8 HRV samples at `hrvValue≤0`) — not worth it for ~8 likely-padding samples.
- **Phase-3 clean-room activity decode** — superseded by the SDK engine; code removed. Do not revive.
- Re-litigating the W7 call-control fallback (Fabian chose to **keep** the SDK path as a documented
  fallback for non-HFP watches) or the deliberately-kept `sleepV3 capture` debug log.

## Handoff format

Per CLAUDE.md: phone model + Android version, adb mode/serial, package tested, exact Gradle commands +
results, whether VeryFit was force-stopped, each task's result with logcat evidence (privacy-redacted:
app tags only, no caller/body/decoded values), and the next smallest testable step.
