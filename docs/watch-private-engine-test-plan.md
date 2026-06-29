# Watch private-engine deferred test plan

Date: 2026-06-26

Fabian asked to defer build/test execution until the end of the watch private-engine work. Use this file as the running checklist for tests and device verification that must be completed before committing/releasing the branch.

This file is the authoritative current verification gate for the active uncommitted watch private-engine branch. Historical green build/test notes in README/roadmap remain useful context but do not count as current-branch verification until recorded here.

## Environment blocker observed

The current Hermes execution environment has no Java/Android toolchain available:

```text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

`gradlew` has been made executable, so the remaining blocker is Java/Android tooling, not wrapper permissions.

## Source/static checks already run

From `dashboard-android`:

```bash
git diff --check
```

Result: passed.

Quarantine/source greps already run:

```bash
grep -RIn 'import com\.ido\.\|import com\.veryfit\.\|import com\.realsil\.\|import com\.sifli\.' app/src/main app/src/test 2>/dev/null || true
grep -RIn 'Log\.i(TAG, "\(ACTIVITY\|HEART_RATE\|SLEEP\|WORKOUT\|SPO2\|HRV\|RESP\|TEMP\|BODY_ENERGY\|BLOOD_PRESSURE\|STRESS\) ' app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine || true
grep -RIn 'isEnableLog = true\|log_save_days = 3' app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine || true
```

Results:

- only `IdoSdkWatchEngine.kt` imports the vendored IDO SDK packages,
- no direct private decoded health-value `Log.i` bodies remain in the watch engine package,
- no always-on IDO SDK file logging remains.

## Lane C — contract/UI review findings (2026-06-26)

Side-by-side review of the Android upload path vs the dashboard
`POST /api/widget/v1/watch/health` route (`dashboard/src/app/api/widget/v1/watch/health/route.ts`).

Wire contract: **aligned**. Every `WatchHealthUploadDto` list/field maps 1:1 onto the server's
`HealthDto` validator and `watch_*` upsert targets (activity, heart-rate, sleep, workouts, and the
seven V3 point metrics incl. blood pressure + stress). Response `{accepted, storedCount}` matches
`WatchHealthResponseDto`. No wire change required.

Bug fixed (not a wire mismatch — an Android-internal fan-out drop):

- `CompositeWatchHealthListener` (the listener actually installed on the engine) did **not** override
  `onBloodPressureReading` / `onStressReading`. The engine decodes and emits both, the uploader
  buffers both, the DTO/route store both — but the composite let those two callbacks hit the no-op
  interface default, so **blood-pressure and stress were silently dropped before reaching the
  uploader** (and the worker path). Added the two missing overrides.

UI gaps fixed:

- `WatchSyncCounts` / the Watch screen tally omitted blood pressure + stress, so even a correct sync
  under-reported the record total. Added `bloodPressure` + `stress` counts, VM listener overrides, and
  "Last sync" rows.
- Dashboard upload success/failure was invisible on the Watch screen (fire-and-forget in
  `UploadingWatchHealthListener`). A BLE sync that decoded data but failed to reach the dashboard read
  as a clean "Completed". Added a `WatchUploadOutcome` sink (`ServiceLocator.watchUploadListener`,
  symmetric with `watchUiListener`) surfaced as an upload row in the "Last sync" card.

## Deferred Lane C tests to add

Local JVM (pure, no device):

- [x] `CompositeWatchHealthListenerTest`: added source coverage that fans **every** callback — including `onBloodPressureReading` and `onStressReading` — out to all delegates, keeps later delegates alive after an exception, and resolves delegates per callback. **Executed green 2026-06-28** (`testDebugUnitTest`).
- [x] `WatchHealthViewModelTest`: added source coverage — BP + stress callbacks increment
      `WatchSyncCounts.bloodPressure`/`.stress` and `total`; a success/failure `WatchUploadOutcome`
      attaches to `lastSync.upload` with the right fields; an outcome arriving before any `lastSync`
      is ignored without crashing; `onCleared` unregisters the upload listener. **Executed green 2026-06-28.**
- [x] `UploadingWatchHealthListenerTest`: added source coverage — `onSyncComplete` flushes one batch
      and reports `onUploadOutcome(succeeded=true, sentCount, storedCount)` on a successful repo upload
      (batch includes BP + stress), reports `succeeded=false`/`error` when the upload throws, no-ops on
      an empty sync, and still flushes buffered records on `onSyncFailed` (benign end-of-sync). Faked
      via a stub `DashboardApiClient` over a real `DashboardRepository`; no production seam added.
      **Executed green 2026-06-28.**
- [ ] W7 notification mirror map/dedup (`WatchNotificationListenerService`): **no JVM test added** —
      logic is bound to Android framework types (`StatusBarNotification`, `Notification.extras`,
      `Telephony`, `PackageManager`) with no pure seam; the documented answer/reject control-flag
      limitation lives in the SDK-coupled `IdoSdkWatchEngine`. Testing would need Robolectric (a new
      framework, disallowed) or a W7-path refactor (out of scope). Deferred to instrumented/hardware.
- [x] `BaseUrlNormalizationTest`: added common JVM coverage for local-dev `http://` allowed when cleartext is explicitly enabled and rejected when disabled, via a pure `allowCleartext` policy seam. **Executed green 2026-06-28.**
- [ ] `IdoSdkWatchEngine.buildInitParam`: SDK DB persistence remains disabled (`isSaveDeviceDataToDB=false`), SDK SP string encryption remains enabled, and sync callbacks still deliver records to upload after a fresh install/rebind.

## Lane D — W7 notification/call-control review findings (2026-06-26)

End-to-end source review of the W7 mirror/send/call-control path:
`WatchNotificationListenerService` → `WatchEngine.sendNotification` / `IdoSdkWatchEngine` →
`WatchControlEvent`/`controlEvents` → `WatchConnectionService`, plus `WatchSyncWorker.pushPendingToWatch`
and the manifest.

Bug fixed (High — privacy):

- `IdoSdkWatchEngine.sendNotification` logged the whole `V3MessageNotice` / `NewMessageInfo` object at
  **info level** (`Log.i(TAG, "... $notice")`). Those `toString()`s include `contact`/`dataText` and
  `name`/`content` — i.e. the **caller name and the full SMS body** — so private message content
  leaked to logcat in **release/private daily-use** builds (info isn't stripped like the health-record
  logs already were). Replaced both with content-free summaries (`category` + `body.length`), which
  still give a hardware tester a "send happened on path X" signal without the payload. This matches the
  existing debug-gating of decoded health-record logs.

Confirmed-correct (no change needed):

- Mirroring scope is appropriately narrow: only `CATEGORY_CALL` / `CATEGORY_MISSED_CALL` and the
  **default SMS app's** posts are forwarded; the app's own notifications and group-summary rows are
  skipped; no telephony/SMS permissions are requested (the notification-access grant is the only
  opt-in). Dedup is per-`sbn.key` within a 5 s window, cleared on `onNotificationRemoved`.
- `WatchSyncWorker.pushPendingToWatch` only runs after the worker's `configured` gate (dashboard
  base-URL **and** token present), so dashboard quote/reminder text reaches the watch only once a
  dashboard origin/auth is set. Reminders are EVENT/DEADLINE-only, capped at 3/run, deduped per server
  date via watch-specific `NotificationState` flags, and marked "pushed" only after the engine accepts
  the send (failed send retries next run; loop breaks early on a dropped link). No buzz-storm path.

Known limitations to verify/accept on hardware (documented, not "working"):

> **⚠ Superseded by the 2026-06-28 hardware results below.** The two bullets here pre-date hardware:
> the Active 4 Pro turned out to need the **`NotificationPara`** send path (not `V3MessageNotice`), and
> its wrist call control runs over **Bluetooth HFP**, not the SDK `controlEvents` path. See
> "Hardware results" below for what actually happens. Kept for history.

- **Call answer/reject from the wrist is only wired while `WatchConnectionService` (always-on link) is
  running.** It is the *only* collector of `engine.controlEvents`; the on-demand paths (Watch screen,
  `WatchSyncWorker`) do not collect them. With mirroring disabled (no notification access / no
  dashboard), a watch tap has no phone-side effect. This is by design but must be stated plainly.
- **The answer/reject/mute buttons only render on the legacy V3 message path.** `supportAnswering/
  HangUp/Mute=true` are set on `V3MessageNotice` only (`useOldV3`, gated on
  `ex_table_main10_v3_notify_msg`). On the modern `NewMessageInfo` fallback there are no support flags
  **and** no incoming-call type (a live `CALL` maps to `TYPE_GENERAL`), so on those devices an incoming
  call shows as a generic message with **no controls** — answer/reject from the wrist is impossible
  there regardless of the `ANSWER_PHONE_CALLS` grant. Which path the Active 4 Pro takes is unknown
  until hardware: capture `onGetFunctionTable` / which `sendNotification` branch logs during a call.

## Deferred Lane D tests / hardware checks

Local JVM (pure, no device):

- [ ] `WatchNotificationListenerService.map`: a `CATEGORY_CALL` post yields `WatchNotificationCategory.CALL`
      with an "Incoming call · …" body; a default-SMS post yields `SMS` with the message text; an
      arbitrary app post and a `FLAG_GROUP_SUMMARY` post map to null (not mirrored).
- [ ] Dedup: two posts with the same `sbn.key` inside 5 s forward once; after `onNotificationRemoved`
      the next post forwards again.

### Hardware results (Fabian's phone + Active 4 Pro, 2026-06-28, SM-G991B / Android 14, debug)

**Headline:** W7 mirroring was *wired but non-functional on this watch* until a send-path fix this
session. Root cause: the Active 4 Pro advertises `V3_support_set_v3_notify_add_app_name=true`, so its
firmware expects notifications via the **`NotificationPara`** path (`BLEManager.sendNotification`), and
**ignores** the legacy `setV3MessageNotice` our engine was using — the SDK accepted the send but the
watch silently dropped it. Two fixes landed in `IdoSdkWatchEngine`:
  1. `enableMessageNotifyStates()` — push `MessageNotifyState{notify_state=ALLOW}` for CALL/SMS/
     MISSED_CALL/GENERIC via `addMessageNotifyState(list,0,0)` after each sync (mirrors VeryFit's
     `RemindDataManager.sendDefaultNotificationState2Device`). The watch gates display on this.
  2. `sendNotification` now uses **`NotificationPara`** (with an app-name `items` entry) when
     `V3_support_set_v3_notify_add_app_name` is set; incoming **calls** route through the dedicated
     call API `setIncomingCallInfo` / `setStopInComingCall` (VeryFit's `sendCallReminder2DeviceNew`).
     Legacy `V3MessageNotice` / `NewMessageInfo` remain as fallbacks for other watches.

- [x] **Logcat privacy (regression):** **PASS.** SMS + call sends log only `sendNotification
      (NotificationPara) category=SMS len=65` / `sendNotification (incoming call) len=20` — **no caller
      name / SMS body**. The app-level summary is info-level/unconditional, so debug == release for this
      line. The native "DEBUG LOG" stream (which *does* print content) is silenced on release via
      `EnableLog(BuildConfig.DEBUG,…)`/`ProtocolSetLogEnable(BuildConfig.DEBUG)` — full on-device
      release confirmation still wants a release APK, but the gating is verified in source.
- [x] **Mirroring (always-on link):** **PASS.** Granted notification access + ANSWER_PHONE_CALLS via
      `adb` (`cmd notification allow_listener …` / `pm grant`), launched the app → `WatchConnectionService`
      runs foreground (`types=00000010` = CONNECTED_DEVICE), auto-connected the watch, synced, and a
      real SMS **displayed on the watch face in real time**. (Required both fixes above; before them the
      send was accepted but nothing showed.) Health sync in the same run uploaded for real
      (`--> POST …/watch/health` → `<-- 201` → `uploaded 833 records (stored=833)`).
- [x] **Which send path fires:** **NotificationPara** for messages (logged `sendNotification
      (NotificationPara)`); incoming calls go through `setIncomingCallInfo`. The old "V3 notice vs new
      message" question is moot — this watch needs neither; it needs `NotificationPara`.
- [x] **Wrist call control (answer/reject/mute):** **PASS — but via Bluetooth HFP, not our SDK path.**
      Calls display on the watch and tapping **reject ends the phone call** (confirmed by Fabian, twice).
      Diagnostic finding: the watch is **also bonded as a Bluetooth Hands-Free (HFP) + HID device**
      (`dumpsys bluetooth_manager`: `(Connected) F4:91:29:51:C6:45 [DUAL] Active 4 Pro (…,Handsfree,Hid)`,
      `BluetoothHeadset … STATE_CONNECTED`), so Android's telephony stack executes answer/reject over
      HFP natively. Our `deviceControlCallBack` **never fires** (verified with a temp raw-event log: no
      `onControlEvent` on a wrist tap) and `WatchConnectionService` logs no control event — i.e. the
      app's SDK-based call-control path (`controlEvents` → telephony actions, `ANSWER_PHONE_CALLS`) is
      **dead code for this watch**, retained only as a fallback for watches that deliver call control
      over the IDO GATT channel. **Open design question:** keep that fallback, or simplify to
      notifications-only and let HFP own calls (see handoff).
- [x] Confirm with mirroring **disabled** (no notification access) a watch control tap is a no-op and
      `WatchConnectionService` is not running. **PASS (baseline, 2026-06-28, SM-G991B).** As shipped on
      the phone today: `settings get secure enabled_notification_listeners` does **not** list
      `dev.jaredhq.dashboardandroid` (only Samsung/Google listeners), `dumpsys activity services
      dev.jaredhq.dashboardandroid` shows **no** `WatchConnectionService`, and `ANSWER_PHONE_CALLS`
      is `granted=false`. A wrist control tap is structurally a no-op while disabled:
      `WatchConnectionService` is the *only* collector of `engine.controlEvents` (verified in source),
      so with the service not running nothing acts on a control event even if the watch emitted one.

### W7 reliability checks (boot re-arm / always-on / worker / network), 2026-06-28

- [x] **Boot + update re-arm:** **PASS.** `BootReceiver` re-arms `WatchConnectionService` on **both**
      triggers without the app being opened: update — `re-arming watch link after
      …MY_PACKAGE_REPLACED`, service foreground with `reasonCode:PACKAGE_REPLACED`; cold reboot —
      `re-arming watch link after …BOOT_COMPLETED`, service foreground with `reasonCode:BOOT_COMPLETED`,
      and Fabian confirmed the "Watch connected" notification reappeared on its own after reboot
      (no app launch). `types=00000010` (CONNECTED_DEVICE) throughout.
- [x] **Always-on link + auto-connect + reconnect:** **PASS (with one transient note).** The
      `connectedDevice` FGS persisted across the whole session including the reboot. On start/boot the
      `maintain()` loop auto-scans + connects (`maintaining link → connect` → scan → `onConnectSuccess`
      → sync). The first SDK/BLE connect *after the cold boot* stalled at `onConnecting` (~2 min, no
      success) while the Classic-BT/HFP link was up; a fresh connect recovered fully
      (`onConnectSuccess` → `syncAllData onSuccess` → notify states). Likely a transient post-reboot BLE
      hiccup — worth a follow-up to confirm the 60 s-backoff loop self-recovers a stalled `onConnecting`
      (it retries on `DISCONNECTED`, but a wedged `CONNECTING` wouldn't trip that). Not reproduced on
      normal (non-boot) reconnects, which were reliable all session.
- [ ] **Background `WatchSyncWorker` sync / busy-guard:** **not exercised this session.** With the
      always-on service holding the single GATT link, the worker no-ops by design (busy-guard,
      source-verified); a clean test needs the service disabled (revoke notification access) then a
      forced worker run. Deferred — lower priority now that always-on is verified.
- [x] **Network egress (light check):** **PASS (light).** The only egress observed during connect/sync
      was the dashboard upload (`--> POST https://srv1464866…:8443/api/widget/v1/watch/health` →
      `<-- 201`); no Alexa/u-blox/Airoha/starcourse/other third-party host appeared in okhttp logs. A
      full on-device packet capture (VpnService/tcpdump) is still the stronger check and remains the
      open decision below; this run adds a data point that nothing unexpected egressed.

## Deferred Android build/test commands

Run these when Java/Android tooling is available:

```bash
cd /home/apolytus/workspace/worktrees/phone-integration-refinement/dashboard-android
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
```

Record results here before final handoff:

- [x] `./gradlew testDebugUnitTest`: **green 2026-06-28** — 89 tests, 0 failures, 0 skipped (JBR 21, Windows). Includes all four Lane-C JVM tests above + `WatchMetricSupportTest` / `WatchSyncDiagnosticsTest`.
- [x] `./gradlew assembleDebug`: **green 2026-06-28** — debug APK built (only warning: pre-existing `setV3MessageNotice` deprecation).
- [x] `./gradlew lintDebug`: **green 2026-06-28** — no errors.

## Deferred dashboard commands

Only required if the sibling dashboard repo changes:

```bash
cd /home/apolytus/workspace/worktrees/phone-integration-refinement/dashboard
npm run lint
npx tsc --noEmit
npm run test:api
```

Record results here if applicable:

- [ ] `npm run lint`:
- [ ] `npx tsc --noEmit`:
- [ ] `npm run test:api`:

## Phone/watch hardware verification

Run on Fabian's real phone/watch before calling the feature done:

1. Fresh install the debug/private build.
2. Force-stop VeryFit so only the dashboard app owns the BLE link.
3. Pair/bind/connect the watch through the dashboard app.
4. Confirm `BLEManager.setIsNeedRemoveBondBeforeConnect(true)` does not cause repeated unwanted watch confirmation prompts.
5. Trigger manual sync from the Watch screen.
6. Let the foreground/periodic service trigger a sync and confirm the re-entrancy guard avoids duplicate/interleaved syncs.
7. Confirm activity, heart rate, sleep, workouts, SpO2, HRV, respiratory, temperature, body energy, blood pressure, and stress records upload only to the configured dashboard origin.
8. Confirm `isSaveDeviceDataToDB=false` still allows decoded sync callbacks/upload and does not create or retain `ido-watch.db` after sync (`adb shell run-as dev.jaredhq.dashboardandroid ls databases`).
9. Confirm `isEncryptedSPData=true` still allows re-launch/reconnect after process death; if an existing install has legacy plaintext SDK prefs, clear app data and re-bind.
10. Confirm release/private daily-use builds do not emit decoded health record bodies to logcat.
11. Confirm release/private daily-use builds do not retain IDO SDK file logs in `filesDir/ido-logs`.
12. Optional but recommended: run a phone-side network capture during connect/sync to check for SDK-internal egress to Alexa/u-blox/Airoha/starcourse/other third-party hosts.

Record results here:

- [x] Fresh install: **PASS** — `./gradlew :app:installDebug` (install -r, data preserved) several times
      this session on SM-G991B / Android 14; current build carries the W7 send-path fix.
- [x] Force-stop VeryFit / single BLE owner: **PASS** — `am force-stop --user 0 com.watch.life` before
      each connect; our app then owns the link (scan→connect→bind→sync succeeds).
- [x] Connect/bind: **PASS** — auto-connect via `WatchConnectionService`; `onConnectSuccess … bound=true`,
      function table cached, repeatable across the session.
- [x] Manual/auto sync: **PASS** — `syncAllData onSuccess` each connect (activity/stress/body_energy/hrv
      mapped; heart_rate_second delivered-but-dropped as documented).
- [x] Periodic/service sync: **PASS (by design)** — the always-on service drives sync on connect + a 6 h
      timer; `WatchSyncWorker` no-ops while the service holds the link (busy-guard, source-verified).
- [x] Dashboard upload: **PASS (real sink)** — `--> POST https://srv1464866…:8443/api/widget/v1/watch/health`
      → `<-- 201` → `uploaded 833 records (stored=833)`. okhttp lines present = not the fake offline sink.
- [x] Logcat privacy: **PASS** — see Lane-D results; sends log `category/len` only, native content stream
      gated off release in source.
- [x] SDK DB persistence disabled / no `ido-watch.db` retained: **PASS** (SM-G991B / Android 14,
      debug v0.1.0, 2026-06-28). `run-as … ls databases` shows only `dashboard-cache.db*` — no
      `ido-watch.db`. Source: `isSaveDeviceDataToDB = false` (`IdoSdkWatchEngine.buildInitParam`).
- [x] SDK encrypted SP relaunch/reconnect: **PASS**. SDK shared-prefs values are ciphertext —
      `common_info.xml lastConnectedDeviceInfo`/`bindMacAddressList` and `bind_info_*.xml
      bind_device_address` are all base64-encrypted; survived the 09:31 install-r relaunch and a
      later sync (files re-read fine). Source: `isEncryptedSPData = true`. (Note: the watch MAC
      appears in *filenames* `device_<MAC>.xml`/`bind_info_<MAC>.xml`, but those are app-private and
      the stored *values* are encrypted.)
- [x] SDK file-log privacy: **PASS (release gating verified in source; debug behaves as designed).**
      `isEnableLog = BuildConfig.DEBUG`, `p.EnableLog(DEBUG, DEBUG, …)`, `ProtocolSetLogEnable(DEBUG)`,
      `log_save_days = if (DEBUG) 3 else 0` → release builds write no `filesDir/ido-logs` and retain 0
      days. On this *debug* build `ido-logs/` may exist (correct); during checks it was present
      transiently then absent. Full release-build on-device confirmation still pending a release APK.
- [~] Network capture: **light PASS** — only egress seen during connect/sync was the dashboard `201`
      POST; no third-party host in okhttp logs. Full VpnService/tcpdump capture still recommended (see
      Open decisions).
- [ ] Blood-pressure + stress samples (if the watch has them) now appear in the Watch-screen "Last
      sync" counts **and** in the dashboard `watch_blood_pressure_readings` / `watch_stress_readings`
      tables (regression check for the composite-listener drop fix).
- [ ] Watch screen shows the dashboard upload result row: green "Uploaded … stored" on success, red
      "Dashboard upload failed: …" when the dashboard origin is unreachable/misconfigured.

## Open decisions before final release

- [x] Release builds forbid cleartext HTTP dashboard origins while debug builds keep local-dev `http://10.0.2.2` support. Implemented with build-type `usesCleartextTraffic` manifest placeholders and a release-only `ApiClientFactory` rejection of `http://`.
- [ ] Decide whether optional SDK-internal AGPS/Alexa/ephemeris code paths need active runtime blocking or whether documented non-use plus network-capture verification is enough for this private build.

## Phase 2 — sync instrumentation + metric support matrix (2026-06-26)

Added durable instrumentation so a real sync proves what the Active 4 Pro emits and what we drop:

- `WatchSyncDiagnostics` — per-sync, counts-only tally of **every** `ISyncDataListener` callback,
  including the previously-silent no-op sinks (GPS, body composition, second-by-second HR, noise,
  swimming, ECG, emotion). Privacy-safe (no decoded values), so it logs in release too.
- `WatchMetric` / `MetricConfidence` — the durable confidence ladder; pure Kotlin, unit-tested.
- `IdoSdkWatchEngine` now logs, at connect: `function table (health flags): …`; at sync end:
  `sync diagnostics: …`, `sync delivered-but-dropped: …`, and `metric confidence: …`.

Verification run (debug build, SM-G991B / Android 14, fresh `installDebug`):

- [x] `./gradlew testDebugUnitTest` — green (incl. new `WatchSyncDiagnosticsTest`, `WatchMetricSupportTest`).
- [x] `./gradlew assembleDebug` — green.
- [x] `./gradlew lintDebug` — green.
- [x] Fresh `installDebug` + real Active 4 Pro sync — connect/bind/sync/upload OK, no crash.
- [x] Function table + diagnostics + confidence lines captured; UI shows Activity/HRV/Body-energy/Stress.

Result → [`watch-metric-support-matrix.md`](watch-metric-support-matrix.md). Headline findings:

- Proven end-to-end (SHOWN_IN_UI): activity day, body energy, stress, HRV.
- Function-table supported but not emitted in the synced window: workout, sleep, SpO₂, noise, swimming.
- Not supported on this watch (flag false): temperature, blood pressure, GPS, emotion.
- **New gap:** `heart_rate_second` (intraday HR) is delivered by the watch and currently dropped —
  the next implementation slice (domain model + dashboard column + upload + UI).

To re-capture on device:

```bash
adb logcat -c
# connect + sync from the Watch screen, then:
adb logcat -d | grep -E "function table \(health flags\)|sync diagnostics|delivered-but-dropped|metric confidence"
```
