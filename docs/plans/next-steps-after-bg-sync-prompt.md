# Next steps — NON-SLEEP focus (egress, matrix close-out, chores) + deferred sleep verify

> Paste into a fresh Claude Code session in `BLE-phone-integration`. Self-contained, but read the
> linked files + memories before acting. Created 2026-06-29, **re-scoped to NON-SLEEP work** at
> Fabian's request (no new sleep data until tomorrow). The sleep nap-collision fix is committed and
> only needs an on-device proof that requires a real nap+night — **deferred to tomorrow** (last
> section). Everything below is doable without new sleep data.

## Where things stand (2026-06-29)

The watch integration is **largely closed out**. Verified end-to-end (SHOWN_IN_UI, see
[`watch-metric-support-matrix.md`](../watch-metric-support-matrix.md)): activity day, body energy,
stress, HRV, workout, SpO₂, **intraday HR** (delta-seconds, [[tier2-intraday-hr]] — done, dashboard
merged as #111), and sleep. W7 notification/call mirroring ([[w7-notification-hardware]]) and the
connect-stall watchdog ([[connect-stall-watchdog]]) are hardware-verified. Background
`WatchSyncWorker` stitched run is hardware-verified ([[item4-bg-worker-status]]).

**Committed this session, NOT pushed/PR'd** (Fabian to PR/rebase — they stack on the in-flight HR
branches):
- dashboard `watch/sleep-and-sync-route-cleanup`: `78f6063` sleep onset key (deferred-sleep), `dbb84c3`
  remove orphaned `/watch/sync` route (**non-sleep — ready to PR now**).
- dashboard-android `watch/sleep-and-bg-sync-verification`: `079b2bc` sleep onset mapper (deferred),
  `77da044` debug sync trigger (**non-sleep — the reusable test hook**), `1ae8554` cleanup proposal doc.

## The reusable debug trigger (force a background sync on demand)

`app/src/debug/DebugSyncTriggerReceiver.kt` enqueues an **unconstrained one-time** `WatchSyncWorker`:
```
adb -s <serial> shell am broadcast -a dev.jaredhq.dashboardandroid.DEBUG_SYNC_NOW \
    -p dev.jaredhq.dashboardandroid -f 0x00000020
```
`-f 0x20` wakes a force-stopped app into a fresh process. **Watch must be awake** (screen-off A4P
advertises too slowly for the 30s scan). This is the no-code way to drive a real sync for any of the
verification tasks below.

## Hard rules (verify before relying — see memory)

- **Deploy with `installDebug`/`adb install -r` — NEVER `adb uninstall`/`pm clear`** (wipes dashboard
  URL+token → fake "success" sink). Prove a real upload via okhttp `--> POST …/watch/health` + `<-- 201`.
  [[upload-config-gotcha]].
- **Before any sync/BLE test:** `adb shell am force-stop dev.jaredhq.dashboardandroid` (and
  `com.watch.life` if it's running), watch `adb logcat`. Durable ground truth: `run-as
  dev.jaredhq.dashboardandroid files/ido-logs/YYYYMMDD.log` (upload has no local retry).
- **Privacy:** decoded health payloads developer-only — never model-facing. Filter logcat to app tags
  (`IdoSdkWatchEngine`, `WatchHealthUpload`, `okhttp.OkHttpClient`, `DebugSyncTrigger`,
  `WatchSyncDiagnostics`); surface only mechanics (counts, POST/201/`stored=`), not values.
- **VPS `srv1464866`: read-only, no `db:push`/mutations from here.** [[vps-no-dbpush]]. Route DB reads
  through Fabian/hermes.
- Commit only when asked. `dashboard` PR-per-feature, user `apolytus`, nothing destructive to `main`.

## Environment quickref — confirmed 2026-06-29

- `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` before `.\gradlew.bat`. `adb` =
  `C:\Users\davo\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Phone **SM-G991B** (Android 14).
  Watch MAC `F4:91:29:51:C6:45`. **adb serial last seen `192.168.20.102:33085`** (LAN; rotates on
  Wi-Fi flap — ask Fabian for the current "IP address & Port"). [[dev-environment]] for the LAN-pair
  workaround.
- Dashboard is **Tailscale-only**; a Wi-Fi flap drops MagicDNS → uploads fail until Fabian refreshes
  Tailscale on the phone. (This also makes WorkManager's `NetworkType.CONNECTED` read unsatisfied —
  why the debug trigger enqueues unconstrained.)
- Worker-map trick: `am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p <pkg> -f 0x20`
  → logcat `WM-DiagnosticsWrkr` (WatchSyncWorker = `watch-sync-periodic`). `WM-WorkerWrapper` internal
  logging is suppressed — don't rely on its absence.

## Primary work — NON-SLEEP (ordered by value)

### 1. Network egress capture (the main open technical item)
Confirm the SDK makes **no** egress to non-dashboard hosts (Alexa/u-blox/Airoha/etc.) during a
connect/sync — only `srv1464866.tail614ebf.ts.net`. Phone is **unrooted** (no `tcpdump`), so use an
on-device VpnService capture app — **ask Fabian to install PCAPdroid** (or confirm one is present).
Procedure: start the capture → force one sync via the debug trigger → stop → review destination hosts.
If no capture tool can be installed, document the blocker and stop (don't hunt alternatives). Resolves
the long-standing "Open decisions" egress item in the test plan + [[item4-bg-worker-status]].

### 2. Close the support matrix — verify the last capable-but-unverified metrics
No code, just a debug-triggered sync with the watch worn/awake, then read `WatchSyncDiagnostics`:
- **Respiratory** (`SDK_MODEL_ONLY`, no capability flag): confirm whether this watch ever emits
  `respiratory_rate(p≥1,m≥1)`. If it does → it already maps+uploads; flip the matrix row. If it never
  does after a worn day, record that and downgrade the "Next action".
- **Heart-rate day (v2)**: expected to never fire on a V3 watch (HR comes via `heart_rate_second`).
  Confirm once and annotate the matrix so it's not re-investigated.
Flip the matrix rows + footnotes based on what actually emits. Closes the matrix for everything the
watch cleanly exposes.

### 3. Minor investigations (low risk, code-reading)
- **Body-energy zero-slot padding** (matrix note 2): 295 raw items → 33 non-zero. Confirm the
  `value <= 0` skip is correct and the padding is benign (not dropped real data). Document.
- **Activity-day flag mismatch** (matrix note 1): `ex_main4_v3_activity_data` reads **false** yet the
  V3 daily rollup emits. Find the correct capability bit (don't gate on it; emission is ground truth).

### 4. Non-sleep chores for Fabian (his to execute)
- PR the **route-delete** (`dbb84c3`) — independent of the sleep change; safe to land on its own.
- Run [`hr2nd-dashboard-cleanup.sql`](hr2nd-dashboard-cleanup.sql) (item 1 — HR garbage-band cleanup;
  pre-flight `SELECT` first).

## Needs Fabian's go-ahead before starting (don't auto-start)
- **New metric end-to-end: Swimming or Ambient noise.** Both are `FUNCTION_TABLE_SUPPORTED` (the watch
  advertises them) but have no domain model / schema / UI. Adding one mirrors the established
  pattern (model → `WatchHealthListener` cb → DTO → dashboard table+route → Watch-screen count →
  verify 201). Previously scoped **out** ("don't overbuild for broad smartwatch support", CLAUDE.md) —
  only start if Fabian wants the metric.

## DEFERRED to tomorrow — needs new sleep data (do NOT start today)
- **Hardware-verify the sleep nap/main-night fix** ([[sleep-nap-collision-fix]]): once the watch has
  logged a real daytime **nap + a main night on the same wake date**, force a sync and confirm **two**
  `watch_sleep_sessions` rows for that date with distinct `started_at` (logcat SLEEP lines are
  developer-only; after Fabian's `db:push`, confirm rows via Fabian/hermes). This is the only missing
  on-device proof of the fix.
- **Sleep `db:push`** (Fabian): additive/nullable `started_at`; full note in
  [`sleep-nap-collision-fix.md`](sleep-nap-collision-fix.md). Order: merge code → `db:backup` →
  `db:push` → spot-check Settings.
- **Optional:** backfill 06-28's clobbered 475-min night from `files/ido-logs/20260628.log`
  (one-row INSERT, HR-SQL style).

## Out of scope (do NOT start)
- W7 leftovers (DFU/firmware, watch-face push); W8 clean-room engine replacement.
- The full `watch_connections`/`watch_raw_events` table drop unless Fabian explicitly picks it.
- GPS/TCX/cloud/generic-smartwatch expansion. Any mutating op against the live VPS.

## Handoff format
Per CLAUDE.md: phone model + Android version, adb mode/serial, package tested, exact Gradle commands +
results, whether VeryFit was force-stopped/disabled (and restored), each task's result with logcat
evidence (app tags only, no decoded values), and the next smallest testable step.
