# Tier-3 close-outs + sleep re-confirm (kickoff prompt)

> Paste into a fresh Claude Code session in `BLE-phone-integration`. Self-contained, but read the linked
> files + memories before acting. Created 2026-06-29 after Tier 2 (intraday HR) was finished and
> hardware-verified. Supersedes the close-out sections of
> [`tier2-finish-and-closeouts-prompt.md`](tier2-finish-and-closeouts-prompt.md).

## Where things stand

- **Tier 1 — connect-stall reliability: DONE, hardware-verified, pushed.** Liveness-reset connect
  watchdog in `IdoSdkWatchEngine` (90 s callback silence → retry; the SDK runs its own auto-reconnect
  that bypasses our `connect()`). Memory `connect-stall-watchdog`.
- **Tier 2 — intraday HR (`heart_rate_second`): DONE, hardware-verified 2026-06-29, committed +
  pushed.** The per-item `offset` is a **delta in seconds since the previous item** — accumulate:
  `recordedAt = midnight + Σoffset`. Gaps >255 s are `offset=255,hr=0` continuation sentinels (must
  still accumulate). Verified end-to-end (day-spread timestamps → `201` → rows). Android mapper
  `watch/gatt133-retry-and-metric-docs` commit `6f7a1e8`. Dashboard side already live (PR **#111**,
  `origin/main` `c764843`). Support matrix row flipped to `SHOWN_IN_UI`. Memory `tier2-intraday-hr`.
  - **A buggy first sync left garbage rows** (raw offset, not accumulated → all timestamps in
    00:00–00:04) for 2026-06-28 and 2026-06-29. Review-only fix drafted, **not run**:
    [`hr2nd-dashboard-cleanup.sql`](hr2nd-dashboard-cleanup.sql).

This session: the cheap close-outs + the sleep re-confirm Fabian flagged ("new sleep data"). No large
new features.

## Hard rules (verify before relying — see memory)

- **Deploy with `installDebug` / `adb install -r` — NEVER `adb uninstall` / `pm clear`.** Uninstall
  wipes the dashboard URL+token and silently degrades uploads to a fake "success" sink. Prove a real
  upload via okhttp `--> POST …/watch/health` + `<-- 201` (no okhttp lines = fake sink). Memory
  `upload-config-gotcha`.
- **Before any sync/BLE test: `adb shell am force-stop --user 0 com.watch.life`, then start `adb logcat`
  FIRST.** The watch tolerates one BLE owner; the SDK also writes the full decoded sync JSON to
  `run-as dev.jaredhq.dashboardandroid files/ido-logs/YYYYMMDD.log` — that's the durable ground-truth
  copy (the okhttp upload has **no** local retry).
- **Privacy:** decoded health payloads are developer-only — never model-facing. Filter logcat to app
  tags (`IdoSdkWatchEngine`, `WatchHealthUpload`, `okhttp.OkHttpClient`).
- **VPS `srv1464866`: do NOT `db:push` or any mutating step** (active coding processes). Read-only
  queries only; the dashboard applies schema from `main` on deploy. Memory `vps-no-dbpush`. The HR
  cleanup SQL is **review-only — hand it to Fabian, do not execute it against the live DB.**
- **Repo freshness:** the Windows box's `dashboard/` `main` drifts behind `origin/main` (was 2 behind
  this session, has been 14). `git fetch` + check before trusting/committing dashboard edits. The
  dashboard feature work for HR is already merged (#111) — don't re-open it.
- **VeryFit decompile is an oracle, not gospel.** Tier 2's first formula came from the *wrong* VeryFit
  code path (`BaseMeasurementPresenter.getOffset` is the manual-measure writer, not the sync decoder)
  and the live probe disproved it. When `veryfit-breakdown/` and the live wire disagree, the **live
  wire wins** — always confirm against a real probe before shipping a mapping.
- Commit only when asked. `dashboard-android` is a git repo on `watch/gatt133-retry-and-metric-docs`;
  `dashboard` uses PR-per-feature.

## Environment quickref (Fabian's Windows box)

- `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` before `.\gradlew.bat`.
- `adb` = `C:\Users\davo\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Phone **SM-G991B**
  (Android 14 / SDK 34).
- **adb is over Tailscale, NOT LAN mDNS.** `adb devices` is usually empty and mDNS discovery is broken
  on this box. Ask Fabian for the current Wireless-debugging **IP:port** (Tailscale IP, e.g. last
  session `100.99.122.34:32869` — the port rotates) and `adb connect <ip>:<port>`. Two transports may
  appear (Tailscale serial + mDNS name); use the `100.x.x.x:port` one with `-s`.
- Package `dev.jaredhq.dashboardandroid`. Launch to Watch tab + reconnect/sync:
  `am start -n dev.jaredhq.dashboardandroid/.MainActivity --es start_route watch` (a fresh launch
  reconnects → auto-syncs). A reinstall (`install -r`) + relaunch also triggers a fresh sync.
- Watch MAC `F4:91:29:51:C6:45` ("Active 4 Pro"). Lockscreen (non-secure): `input keyevent
  KEYCODE_WAKEUP` then `input swipe 540 1600 540 600`.
- **Deterministic connect-failure test:** `adb shell svc bluetooth disable` / `enable` toggles the
  phone radio over adb (forces connect paths without touching the watch).
- **`heart_rate_second` re-delivers the CURRENT day incrementally** every sync (the native sync-head
  advances, e.g. 234→236 items over 7 min) — but **past days do NOT re-deliver** once consumed. So a
  buggy upload of a past day is not self-healing (hence the cleanup SQL).
- Builds green 2026-06-29: `./gradlew testDebugUnitTest assembleDebug lintDebug`; dashboard
  `npx tsc --noEmit` (pre-existing unrelated errors only) + `npx eslint <files>` clean.

## Primary work (ordered)

### 1. Hand off the HR cleanup SQL (quick, do first)

[`hr2nd-dashboard-cleanup.sql`](hr2nd-dashboard-cleanup.sql) is drafted + validated against a stub
SQLite. It (a) wholesale-replaces 2026-06-28 with the 213 correct readings reconstructed from the raw
ido-log JSON (epochs in Australia/Adelaide +0930 to match the DTO) and (b) targeted-deletes only the
2026-06-29 garbage band `[midnight..+255 s]`, keeping the genuine in-band sample. **Do NOT run it on the
VPS** — surface it to Fabian with the pre-flight + verify queries it contains. If he wants it adjusted
(e.g. different TZ, or to also scrub other days), regenerate from `files/ido-logs/*.log`.

### 2. Sleep emission re-confirm (Fabian flagged "new sleep data")

Sleep is `FUNCTION_TABLE_SUPPORTED` but never yet emitted in a synced window. The watch clears its sleep
buffer after the first reader, so **sync ours BEFORE VeryFit** (`am force-stop --user 0 com.watch.life`
on waking, logcat first, then connect + Sync). Confirm `sleep_v3(p≥1,m≥1)` in the diagnostics line and a
populated `WatchSleepSession` (rem_minutes / rem_count / avg_heart_rate / avg_spo2 /
avg_respiratory_rate) → `--> POST <-- 201` → rows in `watch_sleep_sessions`. Then flip the support-matrix
**Sleep** row to `SHOWN_IN_UI`. Memory `sleep-v3-sync-verified` (rich fields already proven once on
2026-06-28; this is a re-confirm against the new night). Capture the decoded SLEEP via logcat/ido-logs
before syncing — no upload retry.

### 3. Server-side cleanup proposal (propose, don't apply)

The dashboard `/api/widget/v1/watch/sync` route is orphaned (its clean-room telemetry caller was
deleted) and `watch_raw_events` / `watch_connections` / `watch_devices` are largely unused. Draft a drop
proposal (schema + route) for Fabian; **do not mutate the live VPS** (memory `vps-no-dbpush`). Note
`watch_devices` is referenced by `watch_heart_rate_readings.device_id` (FK `set null`) — check the FK
graph before proposing any drop.

### 4. Diligence finishers (optional)

- **Background `WatchSyncWorker` test.** Revoke notification access (stops the always-on service), force
  a worker run, confirm it connects + health-syncs from the background, the busy-guard prevents
  contention, and it times out cleanly when out of range. Re-grant after.
- **Full network egress capture.** VpnService/tcpdump during connect/sync to confirm no SDK-internal
  egress to non-dashboard hosts (this session's light check saw only the dashboard `201`).

## Out of scope this session (deferred — do NOT start)

- W7 leftovers (DFU/firmware update, watch-face push); W8 clean-room engine replacement.
- Fix B (recover ~8 HRV samples at `hrvValue≤0`) — not worth it.
- Re-opening the merged HR dashboard work (#111) or the deliberately-kept sleepV3/HR-second debug probes.
- Running the cleanup SQL against the live VPS (it is Fabian's to execute).

## Handoff format

Per CLAUDE.md: phone model + Android version, adb mode/serial, package tested, exact Gradle commands +
results, whether VeryFit was force-stopped, each task's result with logcat evidence (privacy-redacted:
app tags only, no decoded values), and the next smallest testable step.
