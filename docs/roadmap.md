# Roadmap — dashboard-android

Native Android companion for the self-hosted dashboard's Phase 11 widget API
(`/api/widget/v1`). Contract verified against the dashboard server at
`a735a189766ea914baae435b42db7ae6583b562a` (origin/main). See
[`api-contract.md`](api-contract.md) and [`architecture.md`](architecture.md).

## Status

| Slice | State | Commit |
|-------|-------|--------|
| 1 — Scaffold: domain/DTO/mappers, API client (Retrofit + fake), Room cache, EncryptedSharedPreferences settings, Compose Today/Capture/Settings, Glance widget, WorkManager refresh | ✅ done | `d5979a8` |
| 2 — QA fixes: settings save/test race, base-URL validation/no-crash, enriched `/focus/start` + `/capture` + `/chat` responses, doc accuracy | ✅ done | `8dbde6a` |
| 3 — Connection hardening: read-only `testConnection()` probe (no cache/server mutation), resume-driven Today refresh (tab return + app foreground), widget refresh after in-app capture, `dwtk_` token hint, friendly auth/URL/network errors | ✅ done | `bc9e129` |
| 12 — Companion expansion: fast-capture widget + deep links (widget/notification → Capture/Today), notifications bridge (`/notifications` feed → native Reminders) + lock-screen daily quote, brand-matched theme + launcher/notification icon | ✅ written (not yet compiled) | — |

**Build/verification gate:** authored in an environment with **no JDK / Gradle /
Android SDK**, so nothing has been compiled or run. JVM unit tests are written and
ready (`PayloadMappingTest`, `RepositoryTest`, `BaseUrlNormalizationTest`,
`DeviceTokenFormatTest`). First real run must: open in Android Studio → sync →
`./gradlew testDebugUnitTest` → `assembleDebug`, resolving any AGP/Kotlin/
Compose-BOM version skew the local SDK surfaces. See README "Verification status".

## Watch / Active 4 Pro — direction changed (2026-06-24): vendored-SDK, hybrid

> **Pivot.** The watch integration moved from *clean-room first* to **hybrid: vendor the
> IDO/VeryFit SDK now, clean-room high-value paths later** (health-first, private-only). The
> decompiled APK proved the real wire protocol lives in native libs, so lifting the SDK gets
> full watch functionality fast while the native lib does all framing/parsing. The earlier
> clean-room phase ladder below (and in the BLE plan docs) is **superseded, not deleted** — the
> clean-room code is retained as `CleanRoomWatchEngine`, the future independent path.
> Canonical record: [`docs/adr/0001-vendor-ido-sdk.md`](adr/0001-vendor-ido-sdk.md).
> Baseline before the pivot: git tag `pre-ido-sdk-baseline`.

Watch slices (new ladder):

| Slice | State |
|-------|-------|
| W0 — Checkpoint: tag `pre-ido-sdk-baseline` + ADR 0001 | ✅ done |
| W1 — Vendor SDK jar (targeted 4,511-class slice) + native libs + Gradle (greenDAO/gson) | ✅ done, `assembleDebug` green |
| W2 — `WatchEngine` boundary + `IdoSdkWatchEngine` (v2 activity/HR/sleep mapping) | ✅ done, `compileDebugKotlin` green |
| W3 — On-device: SDK init + scan + connect + **bind** | ✅ **verified on Galaxy S21 / Android 14** (bind persists, `bound=true`) |
| W4 — On-device: health **sync completes** → steps/HR/sleep callbacks | ✅ **data flows on device** via VeryFit's `syncAllData(SyncPara)` orchestration — `status = 3` NAK fixed by syncing sequentially. Watch streams real data; `SyncV3HealthTask onSuccess`. (Run still ends `ERROR_SYNC_TASK_FAILED` from a benign post-transfer conn-param step — data already delivered.) |
| W5 — Health upload: domain → DTO → `POST /api/widget/v1/watch/health` (+ dashboard route/schema) | ✅ **code-complete both halves** — Android upload layer (DTOs + client + repository + `UploadingWatchHealthListener` + `ServiceLocator` wiring; `compileDebugKotlin`/`testDebugUnitTest` green) and dashboard `POST /watch/health` route + workout `(user, started_at)` unique constraint on `schema/watch-health-metrics` (`tsc` clean). Idempotent upserts (day=user+date, workout=user+startedAt). **Pending deploy:** merge `schema/watch-health-metrics` → run `drizzle-kit push` → deploy, so the route exists server-side (the app degrades quietly with 404 until then). |
| W6 — V3 metrics via `ISyncDataListener` V3 callbacks | 🔶 **mostly done** — `HealthSleepV3`→`WatchSleepSession`, `HealthActivityV3`→`WatchWorkout`, **and the five intraday point metrics now mapped end-to-end**: `HealthSpO2`→`WatchSpo2Reading`, `HealthHRVdata`→`WatchHrvReading` (→ `watch_stress_readings.hrv_ms`), `HealthRespiratoryRate`→`WatchRespiratoryReading`, `HealthTemperature`→`WatchTemperatureReading` (raw centi-°C ÷100), `HealthBodyPower`→`WatchBodyEnergyReading` (new `watch_body_energy_readings` table). Each emits one domain reading per item with a resolved wall-clock `recordedAt`; upload batch + DTOs + `/watch/health` upserts (idempotent on `(user, recordedAt)`) all wired. `compileDebugKotlin` + `testDebugUnitTest` (new `WatchHealthMappingTest`) green; dashboard `tsc` clean in `src/`. **Still logged-only:** `HealthSportV3` (per-minute daily activity rollup — distinct from the workout sessions), BP V3, body composition, noise, pressure, swimming, ECG, emotion-health. **Timestamp caveat:** within-day offsets use the IDO minute-of-day convention (temperature uses its own `time_offset_unit`); the `date` prefix is always exact, so the unit is confirmable on-device without risking daily rollups. |
| W7 — Other functions already in the lift: notifications, calls, DFU, watch faces (wire `BLEManager` calls) | later |
| W8 — Clean-room replacement of high-value paths, using the SDK as oracle | later |

### Current blockers (W4) — health sync doesn't complete yet

Proven on hardware: SDK loads/inits, native protocol stack runs, scan finds the watch
(`F4:91:29:51:C6:45`), connect succeeds, and **bind succeeds** ("watch claimed by our app",
persisted across launches).

**On-device test 2026-06-24 (Galaxy S21, debug auto-connect scaffold):**

1. **`supportFunctionInfo is null` — ✅ RESOLVED / was a red herring for sync.** The
   `getFunctionTables()` fix works: logcat shows `onGetFunctionTable received (cached=true)`,
   `onConnectSuccess … bound=true`, then `onInitCompleted (bound) — starting health sync`. The
   pulled SDK protocol log (`files/ido-logs/20260624.log`) shows the function table arrives and
   — crucially — **encryption is not required by this watch**: the `SupportFunctionInfo` JSON has
   `BindAuth=false`, `*_encrypted_auth=false`, `support_send_encrypted_data_with_bind=false`, and
   the device is `not ContainEncryptCharacteristic`. So the encrypted handshake was never the gate.
2. **`status = 3` bulk-sync NAK — ✅ FIXED (2026-06-24).** Root cause: `syncHealth()` fired
   `startSyncActivityData()` **and** `startSyncHealthData()` back-to-back; the `08…`/`09…` data-pull
   commands interleaved on the single command channel and the watch NAK'd both with `status = 3`
   for ~12 s until timeout. **Fix:** drive the *orchestrated, sequential* sync the stock VeryFit app
   uses — `BLEManager.syncAllData(SyncPara)` (`DeviceManagerPresenter` /
   `BaseHomeFragmentPresenter`), which runs config → health → activity → V3 as ordered tasks and
   delivers data via `SyncPara.iSyncDataListener` (`ISyncDataListener`). `IdoSdkWatchEngine` now
   calls `syncAllData` (`isNeedSyncConfigData=false`, timeout 300 s) and implements
   `ISyncDataListener` + `ISyncProgressListener`; the direct `CallBackManager`
   `registerSyncActivity/HealthCallBack` registrations were removed (the tasks register their own
   internally and forward to our listener — keeping ours too double-delivers). **On-device result:**
   no more `status = 3`; the watch streamed a full workout (steps/HR/pace), multiple sleep + sport
   sessions, respiratory rate, body power, HRV; `SyncV3HealthTask onSuccess / finished`.
3. **This watch is V3-only for health (key finding).** The data arrives through the **V3**
   callbacks — `handleActivityV3Data` / `handleHealthSleepV3Data` / `handleHealthSportV3Data` /
   `handleHRVData` / `handleRespiratoryRateData` / `handleBodyPowerData` — i.e.
   `ISyncDataListener.onGetHealthActivityV3Data` etc., **not** the v2 `onGetActivityData/HeartRate/
   Sleep` we currently map. Those V3 methods are no-op sinks right now, so the app receives the sync
   but **captures nothing yet**. → **W6 (V3 mapping) is the real data-capture path for this device,
   promoted ahead of the v2 work.** Map `HealthActivityV3` (steps/HR/calories/distance/pace),
   `HealthSleepV3`, `HealthSportV3` (workouts), and the standalone metrics (HRV, respiratory, body
   power, SpO2, temperature) to domain, persisting **incrementally as each record arrives** (so the
   benign end-of-run failure below never costs data).
4. **Residual `ERROR_SYNC_TASK_FAILED` is benign.** After `SyncV3HealthTask onSuccess`, the SDK
   restores the BLE interval to slow (`setTransferSpeedToSlowForEnd`); the watch's conn-param reply
   returns `errorCode=17` ("unsupported remote feature"), it retries to max
   (`set slow transfer mode out of max times for end`), and *that* marks the whole run failed — a
   false-negative; **all data is delivered before it.** Likely the same DUAL-mode/Classic-profile
   contention noted before. Mitigation options: persist V3 data incrementally (above) so onFailed is
   harmless; or investigate suppressing Classic profiles / SPP-priority to stabilise conn-param
   updates. Also seen but non-blocking: `sync config failed!13` (now skipped via
   `isNeedSyncConfigData=false`).

### ⚠ Two BLE stacks coexist (interference risk + planned UI rework)

The old clean-room manual-BLE stack was **not** replaced by the vendored SDK — both are live:
- **Old:** `ble/WatchBleManager` + `ble/WatchGattCallback` (raw `BluetoothGatt`, auto-connects on
  scan match). Instantiated in `ServiceLocator` and **consumed by the UI** (`AppViewModelFactory`)
  and the background **`WatchSyncWorker`**. This is what the current Watch UI actually drives.
- **New:** `watch/engine/IdoSdkWatchEngine` (vendored SDK). Instantiated in `ServiceLocator` but
  **referenced nowhere else** — only the debug auto-connect scaffold exercises it.

Because each can hold an independent GATT connection to the same watch, they can contend if both
run at once (a likely aggravator of the dual-mode instability). They didn't collide in this test
only because the UI/worker stayed idle. **Planned:** point the Watch UI + `WatchSyncWorker` at the
`WatchEngine` interface and retire `WatchBleManager` (or keep it solely as the future
`CleanRoomWatchEngine` behind the same interface). Until then, avoid driving both in one session.

**Scoping (2026-06-24) — this is a redesign, not a repoint:**
- The current **Watch screen** (`WatchViewModel` + `WatchScreen`) is a **clean-room BLE debug
  console** bound to `WatchBleManager`'s raw-packet `logger`, `sendDebugHexCommand`, and manual
  probes (`requestMacAddress/DeviceInfo/BatteryInfo`, `requestActivitySync`). None of that maps onto
  the deliberately minimal `WatchEngine` (`init/connect/disconnect/isConnected/syncHealth` +
  `WatchHealthListener`). Repointing means building a **new product Watch screen** (connection state
  + synced health) and deciding whether to keep the debug console as a dev-only tool.
- ~~`WatchEngine` exposes **no connection-state `StateFlow`** yet~~ — ✅ **done (first additive
  step).** Added `enum WatchEngineConnectionState` (`DISCONNECTED / SCANNING / CONNECTING / BINDING /
  AWAITING_WATCH_CONFIRMATION / CONNECTED / SYNCING`) + `connectionState: StateFlow<…>` on the
  `WatchEngine` interface; `IdoSdkWatchEngine` backs it with a `MutableStateFlow` and emits at every
  lifecycle point (connect→SCANNING, scan-match/onConnectStart→CONNECTING, onConnectSuccess→
  CONNECTED|BINDING, bind onNeedAuth→AWAITING_WATCH_CONFIRMATION, bind onSuccess→CONNECTED,
  startSync→SYNCING, sync end→CONNECTED-if-still-linked, all failures/disconnect→DISCONNECTED). The
  state type is engine-agnostic (carries no transport detail — distinct from the clean-room
  `ble.WatchConnectionState`). Additive only; `compileDebugKotlin` + `testDebugUnitTest` green. **Next
  here:** build the new product Watch screen that collects this flow + drives `connect`/`syncHealth`,
  then retire the debug-auto-connect scaffold.
- `WatchSyncWorker` uploads **connection telemetry** via `WatchBleManager.buildSyncRequest()` — a
  Phase-2 stopgap from before real health data existed. The engine has no equivalent; repointing the
  worker is entangled with **W5** (upload *health* data instead of telemetry). Cleanest is to fold
  the worker rework into W5: replace the telemetry upload with the health-upload path.

Operational notes for the next session:
- ADB is wireless: `192.168.20.100:40367` (rediscover via `adb mdns services` if dropped; do
  **not** run `adb usb` — it drops the wireless link).
- Force-stop VeryFit (`com.watch.life`) before testing; grant `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`.
- A debug auto-connect scaffold (`IdoSdkWatchEngine.DEBUG_AUTO_CONNECT_MAC`) fires connect+sync
  ~4s after launch; remove it once the Watch screen drives connect/sync via the `WatchEngine`.
- The watch is now **bound to our app** — VeryFit will need re-pairing (reopen it) if you want it back.

### Superseded clean-room direction (pre-2026-06-24, kept for reference)

- ~~**Watch / Active 4 Pro private build (clean-room)**~~ — the `0x02` status/identity path is watch-verified and `ble-stability-ready-lifecycle` added ready-gated writes, serialized command queueing, hardened notification setup, and strict `02 01` basic-info parsing; `33 DA AD` activity reassembly + summary decode landed too. This code is retained as the clean-room engine + protocol oracle. See [`docs/plans/ble-master-plan.md`](plans/ble-master-plan.md) (now banner-headed with the pivot).
- **Phone app improvements plan** —
  [`docs/plans/phone-app-improvements.md`](plans/phone-app-improvements.md).
  **Dashboard side: ✅ implemented** (task In Progress state + visible chip;
  Completed Goals section; goal-priority-weighted ranking with an in-progress
  boost; additive `relevantTasks` + `agenda` on `/today`; recurring workout
  plans; visible mobile tool-details toggle in chat). Verified on the dashboard
  with `npm run lint` / `npx tsc --noEmit` / `npm run test:api` (all green).
  **Android side:** the `relevantTasks` DTO is added (additive, defaulted —
  decodes old + new payloads), but the domain model / `TodayMapper` / Compose
  Today UI for the recommended-tasks list are **not yet wired** (and unbuilt —
  no local JDK/Gradle/SDK). See `api-contract.md` "Additive: `relevantTasks`".
- **Real-device verification** — run the JVM tests, build, install; exercise the
  Glance widget actions (habit toggle, focus start) and WorkManager refresh on a
  device against a live dashboard over the Tailscale HTTPS URL. Highest priority.
- **Tasks screen** — a dedicated list beyond Today's single `mainAction`
  (intentionally trimmed for V1).
- ~~**Lock-screen / widget quote UI**~~ — ✅ done (Phase 12): the daily quote is
  posted as a lock-screen-visible notification by the notifications bridge.
- **Focus countdown** — `FocusStartResult.session.fireAt` (epoch seconds) is
  decoded and preserved but not surfaced as an in-app/in-widget timer.
- **In-app device-token mint/revoke** — currently done in the dashboard web UI
  (Settings → Devices) and pasted in; revisit if an in-app flow is wanted.
- **Widget immediacy when offline** — in-app capture refreshes the widget from
  cache only when online (capture requires network); otherwise it catches up on
  the next periodic worker.

## Known risks

- Contract is pinned to dashboard `a735a18`; a server payload change is localized
  to `data/api/dto` + `PayloadMappingTest` by the lenient decoder and the
  `version`/`/v1` pinning.
- Runtime-unverified paths: Glance composition/actions, WorkManager scheduling,
  `LifecycleResumeEffect` refresh, EncryptedSharedPreferences on older OEM ROMs.
