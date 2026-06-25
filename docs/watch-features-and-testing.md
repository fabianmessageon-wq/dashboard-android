# Watch features (VeryFit/IDO lift) — what's new + how to test

Covers the W6/W7 features added on top of the vendored IDO SDK. Two repos are involved:

- **`dashboard-android`** — the companion app (branch `phone-integration-refinement`).
- **`dashboard`** — the self-hosted server (branch `w6/watch-bp-stress`, off `main`).

All of this is built and unit-tested but **not yet verified on hardware**. The route below is the
manual on-device pass. See [`roadmap.md`](roadmap.md) W6/W7 rows for the design detail and
[`adr/0001-vendor-ido-sdk.md`](adr/0001-vendor-ido-sdk.md) for the boundary rationale.

---

## What was added

### W6 — V3 blood pressure + stress metrics (health)
- **Blood pressure** — `HealthBloodPressureV3` → `WatchBloodPressureReading` → existing
  `watch_blood_pressure_readings` (systolic/diastolic per sample).
- **Stress** — IDO "pressure" `HealthPressure` (0–100) → `WatchStressReading` →
  `watch_stress_readings.stress_score` (merges with HRV's `hrv_ms` at a shared `recordedAt`).
- Full slice: engine mappers → uploader → DTO → `POST /api/widget/v1/watch/health` upserts. The only
  schema change is a `(user, recordedAt)` unique constraint on `watch_blood_pressure_readings` for
  idempotency — **needs `db:push` on the next dashboard deploy** (table is empty, so safe).
- Deliberately *not* mapped: body composition (needs a paired scale + unconfirmed value scale),
  emotion-health (categorical mood code, not a 0–100 score), noise/swimming/ECG (no table / niche).

### W7 — notification bridge (phone → watch)
1. **Engine capability** — `WatchEngine.sendNotification(WatchNotification(appName, body, category))`.
   `IdoSdkWatchEngine` picks the watch's message path by capability
   (`SupportFunctionInfo.ex_table_main10_v3_notify_msg` → legacy `setV3MessageNotice`, else modern
   `setNewMessageDetailInfo`) and maps `category` to that path's type code.
2. **Manual test** — Watch screen → **Send test notification** (visible when connected).
3. **Dashboard content push** — after a background health sync (while still linked),
   `WatchSyncWorker` pushes the **daily quote** (once/day) + the **soonest EVENT/DEADLINE reminders**
   (≤3/run), deduped via watch-specific `NotificationState` flags.
4. **Incoming call / SMS mirroring** — `WatchNotificationListenerService` forwards calls, missed
   calls, and the **default SMS app's** texts to the watch face (display-only). Opt-in is the system
   notification-access grant; no telephony/SMS permissions.
5. **Always-on link** — `WatchConnectionService` (a `connectedDevice` foreground service) keeps the
   watch connected with auto-reconnect so calls/texts mirror in real time, and triggers a 6 h
   periodic health sync. Gated on the mirror opt-in + a configured dashboard; self-stops otherwise.

---

## Testing route (on device)

### 0. Prerequisites
- Build/install the debug app:
  `JAVA_HOME=<Android Studio>/jbr ./gradlew.bat :app:installDebug` (or run from Android Studio).
- Wireless ADB at `192.168.20.100:40367` (rediscover via `adb mdns services` if dropped; do **not**
  `adb usb` — it drops the wireless link).
- **Force-stop VeryFit** (`com.watch.life`) first — it will hold/claim the watch otherwise. The watch
  is bound to *this* app now; reopen VeryFit to re-pair it back if needed.
- In the app: Settings → set the dashboard URL + a device token (actions scope), **Test connection**.
- Grant `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` when the Watch tab prompts.
- Useful log filter: `adb logcat -s IdoSdkWatchEngine WatchNotifMirror WatchConnService WatchHealthUpload`

### 1. Health sync incl. BP + stress (W6)
1. Watch tab → **Connect** → confirm the pairing on the watch face if asked → wait for **Connected**.
2. Tap **Sync now**; watch the "Last sync" card tally records. In logcat, look for `BLOOD_PRESSURE`
   and `STRESS` lines (only if the watch actually recorded those — wear it a while first).
3. On the server, confirm rows landed:
   `sqlite3 <data>/dashboard.db "select count(*) from watch_blood_pressure_readings;"` and
   `… "select count(*) from watch_stress_readings where stress_score is not null;"`.
   - ⚠ If BP/stress upserts error on the server, the `db:push` for the new BP unique index hasn't run.
4. Re-sync the same day → counts should be **idempotent** (upserts, not duplicates).

### 2. Manual test notification (W7)
1. While **Connected**, tap **Send test notification**.
2. Expect "Notification sent to watch." and the message on the watch face.
   - If nothing shows: the watch's app/message notifications may be disabled on the device, or it
     needs the *other* message path — check logcat for `sendNotification (new message)` vs
     `(V3 notice)` and whether an exception followed.

### 3. Daily quote + reminders push (W7)
1. Easiest: trigger the worker rather than wait 6 h —
   `adb shell cmd jobscheduler run -f dev.jaredhq.dashboardandroid <job-id>` *or* just **Sync now**
   from the Watch screen after ensuring the dashboard has a quote/reminders for today.
2. Expect the daily quote on the watch (once/day) and up to 3 upcoming EVENT/DEADLINE reminders.
3. Re-run → no repeats (deduped per server date).

### 4. Incoming call / SMS mirroring (W7) — the headline
1. Settings → **Mirror calls & texts to watch** card → **Grant notification access** → enable this
   app in the system list → return to the app (the card should now read "On").
2. Returning to the app foregrounds it → the **always-on service** starts: expect an ongoing
   "Watch connected" notification and the watch linking (logcat `WatchConnService maintaining link`).
3. From another phone: **send an SMS** → it should appear on the watch within a second or two
   (logcat `WatchNotifMirror`). **Place a call** → "Incoming call · <name>" should show.
4. Walk out of range → service should reconnect within ~60 s when back in range (logcat reconnect).
5. Revoke notification access (or clear the token) → next time the app is foregrounded the service
   self-stops and the ongoing notification disappears.

### What to capture if something fails
- `adb logcat` around the action (the tags above).
- Pull the SDK protocol log: `adb pull /sdcard/Android/data/dev.jaredhq.dashboardandroid/files/ido-logs/`
  (path may differ; it's `app.filesDir/ido-logs`).

---

## Known limitations (by design, for now)
- **Display-only calls** — no answer/reject *from the watch* yet (needs the SDK's device→app
  call-control path + an InCallService/`ANSWER_PHONE_CALLS`).
- **No boot receiver** — the always-on link re-arms when the app is next opened, not automatically
  after a reboot.
- **Message path is capability-guessed** — if the Active 4 Pro accepts only one of the two message
  paths, the branch may need adjusting after the first on-device run (one-line change).
- **Dashboard branch** `w6/watch-bp-stress` is not merged/deployed; the BP unique index needs
  `db:push`.
