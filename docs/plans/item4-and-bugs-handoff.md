# Item-4 finish + two bug fixes (kickoff prompt)

> Paste into a fresh Claude Code session in `BLE-phone-integration`. Self-contained, but read the linked
> files + memories before acting. Created 2026-06-29 after items 1–3 of
> [`tier3-closeouts-and-sleep-prompt.md`](tier3-closeouts-and-sleep-prompt.md) closed and item 4 was
> partially verified. Two real bugs were found en route — both are queued below, neither touched in prod.

## Where things stand (2026-06-29)

- **Item 1 — HR cleanup SQL: surfaced, Fabian's to run.** [`hr2nd-dashboard-cleanup.sql`](hr2nd-dashboard-cleanup.sql)
  (review-only; SQLite dialect; assumes single owner — see its pre-flight). Not run.
- **Item 2 — Sleep re-confirm: CLOSED.** `2026-06-29` landed in the **live prod DB**
  (`/home/apolytus/workspace/dashboard/data/dashboard.db`): total 504, deep 97, light 258, **rem 142 /
  count 17**, avg-HR 49, score 90, avg-spo2 NULL (firmware never emits sleep spo2/respir — confirmed both
  nights). Support-matrix **Sleep row flipped to `SHOWN_IN_UI`**. Memory [[sleep-v3-sync-verified]].
- **Item 3 — Server-side cleanup: PROPOSAL written**,
  [`server-side-watch-cleanup-proposal.md`](server-side-watch-cleanup-proposal.md). Verdict: `/watch/sync`
  route is genuinely orphaned (safe delete); `watch_connections`+`watch_raw_events` are write-dead but
  still read by the Settings UI (drop needs 3 small UI/data edits); **`watch_devices` is LIVE — keep it.**
  Awaiting Fabian's pick: route-only vs full drop.
- **Item 4 — background `WatchSyncWorker`: PARTIALLY verified.** Memory [[item4-bg-worker-status]].
  Proven: worker fires from a true background process and drives the engine; full connect→sync path works
  when the link is free; busy-guard holds. **Left:** a stitched *worker-driven* sync run, the 6-min
  out-of-range timeout, and the egress capture.

### Two bugs found (both documented, neither fixed)

1. **Sleep nap/main-night upsert collision (real data loss).** Server upserts sleep on
   `unique(user_id, date)` (`dashboard/src/app/api/widget/v1/watch/health/route.ts` ~L484). This watch
   (`support_display_nap_sleep=true`) emits **naps + a main night with the same wake-date**, so the last
   write wins. Proof: prod `2026-06-28` row = total **32** (a nap) — it clobbered that night's real
   **475**-min main sleep (still in `files/ido-logs/20260628.log`). `2026-06-29` only survived because no
   nap shared its date. **Fix is schema/DTO-side:** discriminate naps (add a session-start or `is_nap`
   discriminator to the unique key + the Android DTO). Propose before touching prod ([[vps-no-dbpush]]).
2. **Orphaned `/watch/sync` route** — see item 3 proposal.

## Hard rules (verify before relying — see memory)

- **Deploy with `installDebug`/`adb install -r` — NEVER `adb uninstall`/`pm clear`** (wipes dashboard
  URL+token → fake "success" sink). Prove a real upload via okhttp `--> POST …/watch/health` + `<-- 201`.
  Memory [[upload-config-gotcha]].
- **Before any sync/BLE test:** `adb shell am force-stop --user 0 com.watch.life`, then `adb logcat`
  FIRST. The SDK also writes decoded sync JSON to `run-as dev.jaredhq.dashboardandroid files/ido-logs/
  YYYYMMDD.log` — the durable ground truth (upload has **no** local retry).
- **Privacy:** decoded health payloads are developer-only — never model-facing. Filter logcat to app tags
  (`IdoSdkWatchEngine`, `WatchHealthUpload`, `okhttp.OkHttpClient`, `WM-WorkerWrapper`).
- **VPS `srv1464866`: read-only only, no `db:push`/mutations.** Memory [[vps-no-dbpush]]. Read access:
  `apolytus@100.75.74.29` is reachable, but my local key is **passphrase-protected with no ssh-agent**, so
  non-interactive ssh can't sign → **route DB queries through Fabian / hermes**, not direct ssh. Prod DB:
  `/home/apolytus/workspace/dashboard/data/dashboard.db` (WAL mode — check `-wal` mtime for freshness).
- **`dashboard` repo drifts behind `origin/main` on this box — `git fetch` before trusting/committing.**
- Commit only when asked. `dashboard-android` git on `watch/gatt133-retry-and-metric-docs`; `dashboard`
  PR-per-feature, **user is `apolytus`, nothing destructive to `main` in `workspace/dashboard`.**

## Environment quickref (Fabian's Windows box) — updated 2026-06-29

- `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` before `.\gradlew.bat`. `adb` =
  `C:\Users\davo\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Phone **SM-G991B** (Galaxy S21,
  Android 14/SDK 34); Tailscale name **`fabians-s21`**. Watch MAC `F4:91:29:51:C6:45`.
- **adb connection (this took ~8 rounds last session — see [[dev-environment]]):** the Windows box shares
  the phone's **LAN** (`192.168.20.100`; phone `192.168.20.10x`). adb reachable via Tailscale IP
  `100.99.122.34:<port>` AND the LAN IP — but **`adb pair` over Tailscale FAULTS**; **pair + connect over
  the LAN IP works** (`adb pair 192.168.20.10x:<pairPort> <code>` then `adb connect 192.168.20.10x:<connPort>`).
  The connect port, pairing port, and LAN IP **all rotate on a Wi-Fi flap** — ask Fabian for the **top
  "IP address & Port"** (connect) and the pairing-port+code if it demands pairing. `tailscale ping
  100.99.122.34` confirms the tunnel.
- **Dashboard is Tailscale-only (no public IP).** A Wi-Fi flap drops the **phone's MagicDNS**, so the app
  can't resolve `srv1464866.tail614ebf.ts.net` (`UnknownHostException`) and uploads fail (and DNS can
  flap). Fabian refreshing/toggling Tailscale on the phone restores it.
- Launch: `am start -n dev.jaredhq.dashboardandroid/.MainActivity --es start_route watch` (routes:
  today|capture|watch|settings). Lockscreen non-secure: `input keyevent KEYCODE_WAKEUP` then
  `input swipe 540 1600 540 600`.

## Primary work (ordered)

### 1. Finish item 4 — background `WatchSyncWorker` (needs a calm device)

Goal: capture in one run the worker **driving** a connect→sync from the background; then the out-of-range
timeout; then the egress capture. The test is fought by **three auto-healing behaviours** (all confirmed
— memory [[item4-bg-worker-status]]): WorkManager dedups `cmd jobscheduler run -f`; **Samsung re-grants
notification access** on app launch (so the always-on service re-grabs the link → worker no-ops);
**VeryFit auto-restarts and `pm disable-user` gets re-enabled** by Samsung's accessory framework.

**Recipe for a clean stitched run:** `pm disable-user --user 0 com.watch.life` (re-check it stays down) →
`cmd notification disallow_listener dev.jaredhq.dashboardandroid/…WatchNotificationListenerService` →
`am force-stop` app → fresh `am start` to a **non-watch** route + `KEYCODE_HOME` (reschedules WM jobs,
service gated off, engine DISCONNECTED) → `dumpsys jobscheduler | grep 'JOB #u0a639/'` for the fresh ids →
`cmd jobscheduler run -f dev.jaredhq.dashboardandroid <id>` for each → watch logcat for `WM-WorkerWrapper`
`tags={…WatchSyncWorker}` + `syncAllData onSuccess`. **Out-of-range:** `svc bluetooth disable`, run the
worker, confirm it waits to its 6-min timeout and returns success (don't need the 201). **Egress capture:**
VpnService/tcpdump during connect/sync — confirm no SDK egress to non-dashboard hosts. **Always restore
after:** `pm enable com.watch.life`, re-grant access (`cmd notification allow_listener …`), re-foreground.
`WM-WorkerWrapper` logs each worker class — that's how to tell the 3 periodic jobs apart.

### 2. Fix the sleep nap/main-night upsert collision (propose → implement)

Schema/DTO change so naps and the main night coexist for one wake-date. Touches `dashboard` schema
(`watchSleepSessions` unique key), the `/watch/health` route upsert, and the Android sleep DTO/mapper.
Propose first (FK + migration note; `db:push` is the apply path, Fabian runs it), then implement once he
agrees. Backfill 2026-06-28's clobbered 475-min night from `files/ido-logs/20260628.log` if wanted.

### 3. Apply the server-side cleanup (Fabian's pick)

Per [`server-side-watch-cleanup-proposal.md`](server-side-watch-cleanup-proposal.md): **route-only**
(delete `sync/route.ts`, no UI change — recommended) or **full drop** (also trim `data.ts` +
`settings/page.tsx` + `watch-devices-section.tsx`, drop the two tables). PR on `dashboard`; **keep
`watch_devices`.**

### 4. Hand the HR cleanup SQL to Fabian to run (item 1 leftover).

## Out of scope (do NOT start)
- W7 leftovers (DFU/firmware, watch-face push); W8 clean-room engine replacement.
- Re-opening merged HR work (#111) or the kept debug probes.
- Running anything mutating against the live VPS (SQL, db:push, drops) — all Fabian's to execute.

## Handoff format
Per CLAUDE.md: phone model + Android version, adb mode/serial, package tested, exact Gradle commands +
results, whether VeryFit was force-stopped (and whether you had to disable it), each task's result with
logcat evidence (app tags only, no decoded values), and the next smallest testable step.
