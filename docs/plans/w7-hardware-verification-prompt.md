# W7 — notification / call-control hardware verification (kickoff prompt)

> Paste this into a fresh Claude Code session in `BLE-phone-integration`. It is self-contained but
> read the linked files before acting. Created 2026-06-28 after health-metric verification finished
> (activity, body energy, stress, HRV, **workout**, **SpO₂** are all `SHOWN_IN_UI`).

## Where things stand

The watch **health** path is essentially done and verified end-to-end on hardware. What remains
unverified is **W7 — the notification / call bridge**, which is fully source-wired but has had **zero
hardware passes**. This is the roadmap's biggest open risk. The authoritative checklist is Lane D +
the hardware section of [`docs/watch-private-engine-test-plan.md`](../watch-private-engine-test-plan.md);
the design is in [`docs/roadmap.md`](../roadmap.md) row W7. All of it lives behind the vendored SDK
engine ([`IdoSdkWatchEngine`](../../app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/IdoSdkWatchEngine.kt),
ADR 0001) — the only file allowed to import `com.ido.*` / `com.veryfit.*`.

**Goal of this session: turn the W7 Lane-D checklist green (or document each item that can't pass and
why), with logcat evidence.** No new features expected — this is verification + small fixes only.

## Hard rules (verify before relying — see memory)

- **Deploy with `installDebug` / `adb install -r` — NEVER `adb uninstall` / `pm clear`.** Uninstall
  wipes the dashboard URL+token (EncryptedSharedPreferences) and silently degrades uploads to a fake
  "success" sink. Prove a real upload via okhttp `--> POST …/watch/health` + `<-- 201` (no okhttp
  lines = fake sink).
- **Before any sync or BLE test: `adb shell am force-stop --user 0 com.watch.life`, then start
  `adb logcat` to a file FIRST.** The watch tolerates one BLE owner, and the DEBUG capture lines are
  the only durable copy of decoded payloads.
- **Privacy:** decoded health payloads and notification contents are developer-only — never surface
  them model-facing. The W7 fix already redacts caller name / SMS body from logcat; the regression
  check below depends on that staying true.
- **VPS `srv1464866`: do NOT run `db:push` or any mutating step** (active coding processes). `main`
  already carries the watch schema; the server deploys from `main`. Read-only queries are fine.
- Commit only when asked; if on `main`, branch first.

## Environment quickref (Fabian's Windows box)

- `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` before `.\gradlew.bat`.
- `adb` = `C:\Users\davo\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Phone **SM-G991B**
  (Galaxy S21, Android 14 / SDK 34) on **wireless adb**; only one device, but `-s` is safe. If
  `adb devices` is empty, `adb reconnect offline` first. Foreground user is **0** — use `--user 0`.
- Package `dev.jaredhq.dashboardandroid`. Launch to Watch tab:
  `am start -n dev.jaredhq.dashboardandroid/.MainActivity --es start_route watch` (routes:
  today|capture|watch|settings). Drive Compose via `uiautomator dump` → tap bounds centre.
- Lockscreen non-secure: `input keyevent KEYCODE_WAKEUP` then `input swipe 540 1600 540 600`.
- Engine logs under tag `IdoSdkWatchEngine`; BLE under `WatchBLE`.

## Primary work — W7 Lane-D hardware checklist

Run with notification access granted + a configured dashboard so `WatchConnectionService` (the
always-on link) is up. First learn **which notification path the Active 4 Pro takes** — capture
`onGetFunctionTable` (`function table (health flags): …`) and which `sendNotification` branch logs
during a call (V3 notice vs new message). That decides whether wrist answer/reject buttons can render
at all (only the legacy V3 path exposes them; the modern `NewMessageInfo` path has no call type / no
support flags).

1. **Logcat privacy regression (high).** Trigger an incoming call + an SMS while linked; confirm
   logcat shows `sendNotification (…) category=… len=…` and **never** the caller name or SMS body —
   on a release/non-debug build.
2. **Notification mirroring.** Grant notification access + configure dashboard; confirm
   `WatchConnectionService` starts and an incoming call / SMS mirrors to the watch face in real time.
3. **Which call path fires.** Record whether a call uses the V3 notice or new-message branch.
4. **Wrist call control (only if V3 path).** Tap answer / reject / mute on the watch; confirm the
   phone call is accepted / ended / silenced (`ANSWER_PHONE_CALLS` granted; mute is best-effort).
5. **Mirroring disabled = no-op.** With notification access revoked, confirm a watch control tap does
   nothing and `WatchConnectionService` is not running.
6. **Always-on link + reconnect.** Confirm the `connectedDevice` FGS persists and auto-reconnects
   (60 s backoff) while the phone is idle; confirm the 6 h periodic sync doesn't starve
   `WatchSyncWorker` (worker no-ops while the service holds the GATT link).
7. **Boot re-arm.** Reboot the phone; confirm `BootReceiver` re-arms `WatchConnectionService` without
   reopening the app (notification access + configured dashboard gate).
8. **Background worker sync.** Confirm `WatchSyncWorker` can connect + health-sync from the
   background, the busy-guard prevents contention, and it times out cleanly when out of range.
9. **SDK DB/log privacy.** Confirm `isSaveDeviceDataToDB=false` leaves no `ido-watch.db`
   (`adb shell run-as dev.jaredhq.dashboardandroid ls databases`), `isEncryptedSPData=true` survives
   process death/relaunch, and release builds keep no `filesDir/ido-logs`.
10. **Network capture (recommended).** During connect/sync, check for SDK-internal egress to
    Alexa/u-blox/Airoha/starcourse/other third-party hosts; decide if active blocking is needed or
    documented non-use + this capture suffices.

Record each result in the test-plan doc's Lane-D / hardware sections (check off or document why not).

## Secondary close-outs (cheap, do if time)

- **Sleep emission.** Sleep is the last health metric whose matrix row still reads "none yet" while
  memory `sleep-v3-sync-verified` says rich rem_*/avg_* landed (201+stored, 2026-06-28). Sync after a
  night's sleep (force-stop VeryFit first — the watch clears its sleep buffer after the first reader
  wins), confirm `sleep_v3(p≥1,…)` + a `<-- 201`, and update the
  [support matrix](../watch-metric-support-matrix.md) Sleep row to `SHOWN_IN_UI`.
- **Remove redundant debug log.** `IdoSdkWatchEngine.onGetHealthSleepV3Data` still has the original
  `sleepV3 capture: …` DEBUG log (~line 574); now that the rich fields map + upload + log via the
  normal record path it's redundant (harmless, DEBUG-gated). Remove it.
- **Server-side cleanup (read-then-decide, no mutation without asking).** The dashboard
  `/api/widget/v1/watch/sync` route is orphaned (the clean-room telemetry chain that called it was
  deleted) and the `watch_raw_events` / `watch_connections` / `watch_devices` telemetry tables are
  unused. A future server-side change can drop them — propose, don't apply on the live VPS.

## Out of scope this session (deferred — do NOT start)

- **Fix B** (recover ~8 HRV samples at hrvValue≤0) — needs native HRV type codes verified; not worth
  it for 8 likely-padding samples. Stays deferred.
- **Intraday HR mapping** (`heart_rate_second`) — unmappable without a VeryFit btsnoop capture; kept
  instrumented only.
- **Phase-3 clean-room activity decode** — superseded by the SDK engine; code removed. See the
  banner in [`ble-phase-3-prompt.md`](ble-phase-3-prompt.md). Do not revive.
- **W7 leftovers DFU + watch faces**, and **W8** clean-room replacement — later, not now.

## Handoff format

Per CLAUDE.md: phone model + Android version, adb mode/serial, package tested, exact Gradle commands
+ results, whether VeryFit was force-stopped, the notification path the watch took, each Lane-D item's
result with logcat evidence (privacy-redacted), and the next smallest testable step.
