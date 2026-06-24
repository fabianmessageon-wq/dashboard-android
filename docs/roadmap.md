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
| W4 — On-device: health **sync completes** → steps/HR/sleep callbacks | ⚠ function-table fix **verified on device** (`onGetFunctionTable cached=true`); sync still fails — new blocker: device NAKs bulk-sync commands with `status = 3`. See "current blockers" |
| W5 — Health upload: domain → DTO → `POST /api/widget/v1/watch/health/*` (+ dashboard route/schema/migration) | ▶ after W4 |
| W6 — V3 metrics (SpO2, body comp, stress/HRV, temperature, respiratory rate, BP V3) via `SyncV3CallBack` | later |
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
2. **NEW REAL BLOCKER — device rejects the bulk-sync pull commands with `status = 3`.** With the
   link up (`BluetoothGatt onConnectionUpdated … status=0`, stays connected) and the function
   table cached, the **control** commands succeed (set-time `03 01 …`, get `02 f4`) but the
   **data-pull** writes fail and retry for ~12 s until timeout:
   - `[APP_SEND_DATA] send => 09 06 01 00` (health) → `onDeviceResponseOnLastSend[failed]( 09 06 01 00 ) , status = 3`
   - `[APP_SEND_DATA] send => 08 01 01 00` (activity) → `…[failed]( 08 01 01 00 ) , status = 3`
   - …repeating, then `[SYNC_DATA] sync health data failed!` and `… sync activity data failed!`.
   `status` originates in `BytesDataConnect` (`com.ido.ble.bluetooth.connect.inkwell`); 0 = ok,
   nonzero = device/transport rejection (not yet pinned: GATT write status vs. parsed response code).
   **The old manual stack did NOT run during this test** (no `WatchBleManager`/`WatchGattCallback`
   log lines), so this is not cross-stack interference — it's the SDK's own sync path.

   **Leading hypotheses / next experiments (cheapest first):**
   - **Concurrent sync collision.** `syncHealth()` fires `startSyncActivityData()` **and**
     `startSyncHealthData()` back-to-back; their commands (`08…`/`09…`) interleave and both NAK.
     Try **sequential** sync — health first, then activity from the health `onSuccess`/`onStop`.
   - **Missing pre-sync config push.** VeryFit likely runs `startSyncConfigInfo()` and/or pushes
     user info after bind before the watch releases health data. Extract VeryFit's real post-bind
     order from the decompiled app and mirror it.
   - Pin the exact meaning of `status = 3` (find the GATT write callback / response parser that
     feeds `inkwell.basilisk(byte[], int)`).
3. **Connection instability (dual-mode)** — previously seen `onConnectSuccess` firing repeatedly
   as the watch's Classic BR/EDR profiles re-attach. **Not reproduced in this test** (single clean
   `onConnectSuccess`, link stable), so deprioritised behind the `status = 3` blocker. Kept for
   reference: SPP-priority (`isSppPriority`/`connectBT`) or suppressing Classic profiles during sync.

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
