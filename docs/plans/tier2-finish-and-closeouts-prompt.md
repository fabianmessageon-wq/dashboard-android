# Finish intraday HR + close-outs (kickoff prompt)

> Paste into a fresh Claude Code session in `BLE-phone-integration`. Self-contained, but read the
> linked files + memories before acting. Created 2026-06-28 after Tier 1 (connect-stall watchdog) was
> shipped + hardware-verified and Tier 2 (intraday HR) was scaffolded. Supersedes the Tier-2 part of
> [`post-w7-reliability-and-intraday-hr-prompt.md`](post-w7-reliability-and-intraday-hr-prompt.md).

## Where things stand

- **Tier 1 — connect-stall reliability: DONE, hardware-verified, pushed.** A liveness-reset connect
  watchdog in `IdoSdkWatchEngine` catches a wedged SCANNING/CONNECTING (fires on 90 s of callback
  silence, reuses the GATT-133 retry budget). Key discovery: **the SDK runs its own internal
  auto-reconnect that bypasses our `connect()`**, so the watchdog re-arms on every connect callback
  from either path. Verified via phone-BT-off (watchdog fired at exactly 90 s → retry → BT on →
  reconnect + real `201`). Memory `connect-stall-watchdog`. Android branch
  `watch/gatt133-retry-and-metric-docs` (commits `362157c` W7, `e0dbfa8` Tier 1, `7e0a60f` Tier 2).
- **Tier 2 — intraday HR (`heart_rate_second`): SCAFFOLDED + pushed; ONE function left.** The full
  pipeline is wired end to end on both sides EXCEPT the engine's per-item offset→timestamp mapper.
  Memory `tier2-intraday-hr`. Dashboard branch `watch/intraday-hr-second` (commit `dc82829`, **PR not
  merged**).

This session: **finish Tier 2** (the mapper + verify + merge), then the cheap close-outs. No large
new features.

## Hard rules (verify before relying — see memory)

- **Deploy with `installDebug` / `adb install -r` — NEVER `adb uninstall` / `pm clear`.** Uninstall
  wipes the dashboard URL+token and silently degrades uploads to a fake "success" sink. Prove a real
  upload via okhttp `--> POST …/watch/health` + `<-- 201` (no okhttp lines = fake sink). Memory
  `upload-config-gotcha`.
- **Before any sync/BLE test: `adb shell am force-stop --user 0 com.watch.life`, then start `adb
  logcat` FIRST.** The watch tolerates one BLE owner; DEBUG capture lines are the only durable copy of
  decoded payloads (upload has **no** local retry).
- **Privacy:** decoded health payloads are developer-only — never model-facing. Filter logcat to app
  tags (`IdoSdkWatchEngine`, `WatchHealthUpload`, `okhttp.OkHttpClient`).
- **VPS `srv1464866`: do NOT `db:push` or any mutating step** (active coding processes). The dashboard
  applies schema from `main` on deploy; merging the PR is what ships the new unique index. Read-only
  queries only. Memory `vps-no-dbpush`.
- **Repo freshness:** the Windows box's `dashboard/` `main` drifts behind `origin/main` (was 14 behind
  last session). `git fetch` + fast-forward + re-validate before trusting/committing dashboard edits.
- Commit only when asked; the Windows box's `dashboard-android` is a git repo on the feature branch
  above, `dashboard` uses PR-per-feature.

## Environment quickref (Fabian's Windows box)

- `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` before `.\gradlew.bat`.
- `adb` = `C:\Users\davo\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Phone **SM-G991B**
  (Android 14 / SDK 34) on **wireless adb**. If `adb devices` is empty, `adb reconnect offline`; after
  a reboot wireless adb stays down until the phone is unlocked — ask Fabian to toggle Wireless
  debugging or give the new IP:port.
- Package `dev.jaredhq.dashboardandroid`. Launch to Watch tab + reconnect/sync:
  `am start -n dev.jaredhq.dashboardandroid/.MainActivity --es start_route watch` (a fresh launch
  reconnects → auto-syncs).
- Watch MAC `F4:91:29:51:C6:45` ("Active 4 Pro"). Lockscreen: `input keyevent KEYCODE_WAKEUP`.
- **Deterministic connect-failure test (from Tier 1):** `adb shell svc bluetooth disable` / `enable`
  toggles the phone radio over adb — useful to force connect paths without touching the watch.
- Builds green as of 2026-06-28: `./gradlew testDebugUnitTest assembleDebug lintDebug`; dashboard
  `npx tsc --noEmit` (pre-existing unrelated errors only) + `npx eslint <files>` clean.

## Primary work (ordered)

### 1. Finish intraday HR — crack the offset→timestamp mapper (Tier 2, top priority)

The whole pipeline is built; only the per-item wall-clock is missing, and it MUST be measured, not
guessed (a wrong offset unit silently mis-times every sample).

**Step A — capture a real probe.** The data won't re-deliver on demand: last session, 4 syncs
delivered only `activity_day_v3`; all history incl. `heart_rate_second` was already consumed (same
buffer behaviour as `sleep-v3-sync-verified`). The **enhanced probe is already deployed**, so the next
natural delivery (background 6 h sync, or a new day boundary — sync in the morning) logs a
`heartRateSecond probe:` line. Force-stop VeryFit, `adb logcat` first, then sync. Look for:
`offsetRange=[..]`, `stepHisto=[..]`, `fiveMin=N`, `itemsHead/itemsTail=[offset=hr,..]`, and
`hrData=[h:m=hr(t..),..]` (the high/low entries carry true wall-clock — the alignment ground truth).

**Step B — derive + implement.** In `IdoSdkWatchEngine.onGetHealthHeartRateSecondData`
([`watch/engine/IdoSdkWatchEngine.kt`](../../app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/IdoSdkWatchEngine.kt))
the candidate is `recordedAt = WatchTime.localDateTime(year, month, day, (startTime + offset)*UNIT)`
with `UNIT` 60 or 300 s — confirm `UNIT` from `stepHisto` (step 1 → index*? ; the `fiveMin` list size
≈288 hints 5-min granularity) and whether `startTime` participates, by checking that an item's derived
time matches the `hrData` hour:minute. The exact emit snippet is already written in the probe comment
— add it (skip `heartRateVal !in 1..250`), set `mappedReadings = mapped` in the diagnostics line, and
keep the probe.

**Step C — verify end-to-end.** Decoded `HEART_RATE_SECOND` items in logcat → `WatchHealthUpload`
buffers → `--> POST` → `<-- 201` → rows in dashboard `watch_heart_rate_readings`. Confirm the
Watch-screen "Heart-rate samples" count is non-zero. Flip the
[support matrix](../watch-metric-support-matrix.md) `heart_rate_second` row to `SHOWN_IN_UI`.

**Step D — merge the dashboard PR.** `watch/intraday-hr-second` (commit `dc82829`) adds the unique
`(userId, recordedAt)` index + route wiring. Merge once Step C passes; deploy applies the index via
`db:push` (table is empty, so safe). Commit the Android mapper to
`watch/gatt133-retry-and-metric-docs` and push.

### 2. Cheap close-outs (Tier 3)

- **Sleep emission re-confirm.** Sync after a night's sleep (force-stop VeryFit first — the watch
  clears its sleep buffer after the first reader; memory `sleep-v3-sync-verified`), confirm
  `sleep_v3(p≥1)` + `<-- 201`, flip the matrix Sleep row to `SHOWN_IN_UI`.
- **Server-side cleanup (propose, don't apply).** The dashboard `/api/widget/v1/watch/sync` route is
  orphaned (clean-room telemetry caller deleted) and `watch_raw_events` / `watch_connections` /
  `watch_devices` are largely unused. Draft a drop proposal; **do not mutate the live VPS**.

### 3. Diligence finishers (Tier 3, optional)

- **Background `WatchSyncWorker` test.** Revoke notification access (stops the always-on service),
  force a worker run, confirm it connects + health-syncs from the background, the busy-guard prevents
  contention, and it times out cleanly out of range. Re-grant after.
- **Full network egress capture.** VpnService/tcpdump during connect/sync to confirm no SDK-internal
  egress to non-dashboard hosts (this session's light check saw only the dashboard `201`).

## Out of scope this session (deferred — do NOT start)

- W7 leftovers (DFU/firmware update, watch-face push); W8 clean-room engine replacement.
- Fix B (recover ~8 HRV samples at `hrvValue≤0`) — not worth it.
- Re-litigating the W7 HFP call-control fallback or the deliberately-kept sleepV3/probe debug logs.
- Guessing the HR-second offset unit without a real probe (the whole point of Step A).

## Handoff format

Per CLAUDE.md: phone model + Android version, adb mode/serial, package tested, exact Gradle commands +
results, whether VeryFit was force-stopped, each task's result with logcat evidence (privacy-redacted:
app tags only, no decoded values), and the next smallest testable step.
