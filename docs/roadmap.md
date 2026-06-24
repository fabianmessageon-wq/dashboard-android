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
| W1 — Vendor SDK: `ido-watch-sdk.jar` + native libs + Gradle wiring (greenDAO/gson) | ✅ done, `assembleDebug` green |
| W2 — `WatchEngine` boundary + `IdoSdkWatchEngine` (v2 activity/HR/sleep mapping) | ✅ done, `compileDebugKotlin` green |
| W3 — Health upload: domain → DTO → `POST /api/widget/v1/watch/health/*` (+ dashboard route/schema/migration) | ▶ next |
| W4 — Hardware verify on Fabian's phone (VeryFit force-stopped): init → connect → sync → steps/HR/sleep | ▶ needs device |
| W5 — V3 metrics (SpO2, body comp, stress/HRV, temperature, respiratory rate, BP V3) via `SyncV3CallBack` | later |
| W6 — Other functions already in the lift: notifications, calls, DFU, watch faces (wire `BLEManager` calls) | later |
| W7 — Clean-room replacement of high-value paths, using the SDK as oracle | later |

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
