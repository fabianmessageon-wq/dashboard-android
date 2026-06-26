# VeryFit private-engine workflow — personal watch bridge

Date: 2026-06-26

## Goal

Make Fabian's Kogan Active 4 Pro watch work reliably with the private dashboard without spending more time reverse-engineering every BLE/protocol byte.

The product direction is:

- keep the app's own Kotlin/Compose dashboard UI and dashboard API integration,
- use the extracted VeryFit APK as the working reference/oracle,
- keep only the IDO/VeryFit watch engine pieces needed for Fabian's watch,
- remove/avoid VeryFit branding, ads, membership, marketplace, maps, analytics, vendor cloud, and third-party telemetry,
- send Fabian's watch data only to Fabian's configured dashboard origin unless a future feature is explicitly approved.

This is a personal/private build, not a public app distribution strategy.

## Important clarification

Do **not** rebuild or rebrand the full 300 MB VeryFit APK as the main path.

The extracted tree at `/home/apolytus/workspace/veryfit-breakdown/` is useful as reference, but it is not a clean source project. It contains tens of thousands of decompiled Java files, ad SDKs, maps, marketplace code, analytics, obfuscated helper packages, and native libraries. Deleting from that app until it compiles would likely become another large time sink.

Instead, the fast path is a targeted transplant:

1. Keep this repo's Kotlin Android app as the product shell.
2. Keep the vendored IDO SDK jar/native libs behind `WatchEngine` / `IdoSdkWatchEngine`.
3. Use the decompiled VeryFit app to answer exact SDK workflow questions:
   - how VeryFit initializes the SDK,
   - how it binds/reconnects,
   - how it runs sync in the correct order,
   - which callbacks correspond to heart/sleep/activity/stress/BP/SpO2/etc.,
   - which notification/call/watch functions are safe/useful.
4. Reimplement the surrounding workflow in our code, with only dashboard network calls.

## Current source anchors

Android app:

- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/WatchEngine.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/IdoSdkWatchEngine.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/WatchHealthModels.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/UploadingWatchHealthListener.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/ui/watch/WatchHealthScreen.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/ui/watch/WatchHealthViewModel.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/work/WatchSyncWorker.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/work/WatchConnectionService.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle.kts`

Dashboard API/server:

- sibling repo: `/home/apolytus/workspace/worktrees/phone-integration-refinement/dashboard`
- watch API route: `/api/widget/v1/watch/health`
- keep the dashboard as the only intended remote sink for private health/watch data.

VeryFit reference/oracle:

- APK: `/home/apolytus/workspace/veryfit-breakdown/veryfit-3-4-0.apk`
- Decompiled sources: `/home/apolytus/workspace/veryfit-breakdown/veryfit-decompiled/sources/`
- Useful packages to inspect first:
  - `com/ido/ble/**`
  - `com/veryfit/multi/**`
  - `com/ido/life/ble/**`
  - `com/ido/life/manager/device/**`
  - `com/ido/life/notification/**`
  - `com/ido/life/service/**`
  - `com/ido/life/data/health/**`
- Avoid importing app/UI/cloud/ad/membership code from `com.ido.life` into production. Use it to understand call sequences only.

## Non-goals

- No full clean-room reimplementation of every protocol family right now.
- No public app-store distribution of proprietary blobs.
- No VeryFit cloud/account dependency.
- No ad/analytics/marketing SDK initialization.
- No upload of contacts, GPS tracks, raw BLE logs, or raw private health payloads to model-facing systems by default.
- No broad multi-watch/general-user abstraction unless it directly helps Fabian's current watch work.

## Workstreams for Claude agents

Hermes owns orchestration, scope control, source review, and verification. Claude Code may implement focused slices, but its self-report is not enough; Hermes must inspect diffs and run/source-review verification afterward.

### Lane A — workflow parity and reliability

Use the VeryFit decompiled code as an oracle for SDK sequence correctness. Compare our `IdoSdkWatchEngine`, `WatchSyncWorker`, and `WatchConnectionService` against the VeryFit workflow for:

- SDK initialization order,
- bind/rebind behavior,
- reconnect behavior,
- function-table/capability loading,
- health sync orchestration,
- callback registration/unregistration,
- long-running foreground connection behavior,
- benign vs real sync errors.

Deliverables:

- source changes only when a concrete mismatch is found,
- tests where feasible for state/retry logic,
- a short report listing VeryFit source files inspected and what was copied conceptually, not literally.

### Lane B — privacy and dependency pruning

Audit the private build for accidental third-party data paths and unnecessary baggage.

Check:

- Android manifest permissions/services/receivers,
- app dependencies and vendored native libs,
- `IdoSdkWatchEngine` logging and SDK log retention,
- dashboard/network stack so the configured dashboard origin is the only app-owned backend,
- whether any ad/analytics/cloud SDK classes are present or initialized,
- whether raw watch data, contacts, SMS content, call details, GPS, or logs could leak outside the phone/dashboard path.

Deliverables:

- privacy hardening patches where safe,
- a concise allowlist/denylist note for libraries/native libs and network sinks,
- tests/static checks where feasible.

### Optional Lane C — dashboard contract/UI polish

Only start if Lane A/B reveal an integration gap.

Check:

- dashboard watch-health endpoint fields vs Android DTOs,
- idempotency of health uploads,
- Watch tab status/error messaging,
- user controls for sync/mirroring/log capture.

## Acceptance criteria

Before calling this direction successful:

1. Docs clearly state the new private-engine goal and the “do not rebuild full VeryFit APK” constraint.
2. `IdoSdkWatchEngine` remains the only app file importing `com.ido.*` / `com.veryfit.*`, unless a documented exception is deliberately approved.
3. No VeryFit ad/cloud/branding/membership code is brought into the app shell.
4. Build/test verification is run where available:
   - `./gradlew testDebugUnitTest`
   - `./gradlew assembleDebug`
   - `./gradlew lintDebug` when practical
   - dashboard `npm run lint` / `npx tsc --noEmit` only if dashboard server code changes
5. Hardware-only claims are clearly labeled until verified on Fabian's phone/watch.
6. Final handoff includes:
   - changed files,
   - commands run and results,
   - remaining risks,
   - exact on-device workflow for Fabian/Claude to test.

## 2026-06-26 source-level audit results — build/device verification deferred

Workflow parity findings from the VeryFit source oracle:

- Adopted `BLEManager.setIsNeedRemoveBondBeforeConnect(true)` after SDK init, matching stock VeryFit and reducing stale-bond reconnect failures after official-app use or firmware updates.
- Added a `syncHealth()` re-entrancy guard so manual sync, foreground-service sync, and post-bind auto-sync cannot start overlapping `syncAllData()` / duplicate `getFunctionTables()` calls.
- Confirmed the current `syncAllData(SyncPara)` path already matches VeryFit's core orchestration: progress listener, data listener, and 300-second timeout.
- Kept `isNeedSyncConfigData=false` deliberately. VeryFit can dynamically config-sync, but this watch's config-sync path is a known failing push path and is not needed for private health reads.

Because the current Hermes environment lacks Java/Android tooling and Fabian asked to run tests last, these findings are source/static-review results until the checklist in [`docs/watch-private-engine-test-plan.md`](../watch-private-engine-test-plan.md) is completed.

Privacy audit findings and hardening:

- Detailed decoded health record logs are now debug-only in both the upload listener and the fallback logging listener.
- IDO SDK file logging is now debug-only; release/private daily-use builds should not retain extra SDK logs in `filesDir/ido-logs`.
- `app/libs/ido-watch-sdk.jar` contains some hardcoded third-party URL strings (Alexa and AGPS/ephemeris providers). App-owned code does not call those paths and the jar contributes no manifest auto-init components, but only on-device network instrumentation can prove no SDK-internal egress during connect/sync.
- The jar also contains dead `com.ido.map.*` facade classes, but the real AMap/autonavi SDK roots are absent and app-owned code does not reference them.
- Release/private daily-use cleartext dashboard traffic is blocked twice: release builds set `android:usesCleartextTraffic="false"` through the manifest placeholder, and `ApiClientFactory.normalizeBaseUrl` rejects `http://` when `BuildConfig.DEBUG` is false. Debug builds still allow local-dev cleartext origins such as `http://10.0.2.2`.
- IDO SDK health DB persistence is disabled (`isSaveDeviceDataToDB=false`) because the app consumes decoded records directly from `SyncPara.ISyncDataListener` and does not query the SDK database. SDK SharedPreferences string encryption is enabled (`isEncryptedSPData=true`); if existing private installs have legacy plaintext prefs, clear app data and re-bind during hardware verification. SDK DB encryption remains off unless SQLCipher dependency/native libs are deliberately added and verified.

## Verification commands

From `dashboard-android`:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
```

From sibling `dashboard`, only if dashboard code changes:

```bash
npm run lint
npx tsc --noEmit
npm run test:api
```

If local Android tooling is unavailable, perform source-level review and clearly mark build/device verification as pending. Keep the deferred checklist in [`docs/watch-private-engine-test-plan.md`](../watch-private-engine-test-plan.md) up to date until the final test pass.
