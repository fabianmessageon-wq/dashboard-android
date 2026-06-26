# ADR 0001 — Vendor the IDO/VeryFit watch SDK (hybrid, health-first, private)

- **Status:** Accepted
- **Date:** 2026-06-24
- **Decider:** Fabian
- **Supersedes:** the clean-room-first direction in `docs/plans/ble-master-plan.md` and
  `docs/plans/ble-protocol-reverse-engineering-plan.md` (those remain valid as protocol
  reference and as the clean-room-later target — not deleted).
- **Baseline tag:** `pre-ido-sdk-baseline` marks the last commit before this pivot.

## Context

The target watch is a Kogan Active 4 Pro — a rebranded IDO/VeryFit device (SiFli chip). The
only software that fully drives it is the VeryFit app (`com.watch.life` v3.4.0), which depends
on vendor cloud + third-party services and has no path into Fabian's self-hosted dashboard.

We decompiled the APK (JADX). The decisive finding: **the watch's real wire protocol lives in
native libraries** (`libVeryFitMulti.so`, `libprotocol.so`). The decompiled Java (`com.ido.ble.*`)
is a thin facade — `BLEManager` (~3,700 lines) exposes every function as a static call, but each
bottoms out at the JNI boundary `com.veryfit.multi.nativeprotocol.Protocol` (a `static{}` native
loader + `native` methods such as `StartSyncHealthData`, `WriteJsonData`, `ReceiveDatafromBle`).
The native code also calls **back** into fixed-name Java statics, e.g.
`com.ido.ble.callback.SyncCallBack.onGetHeartRateData(HealthHeartRate, …)`.

Consequence: a pure clean-room reimplementation must re-derive the binary framing for every
command/metric from captures — slow, and impractical for some features (DFU, watch faces). The
SDK, by contrast, hands back **fully typed, already-decoded** health objects through its callbacks.

## Decision

1. **Hybrid — lift now, clean-room later.** Vendor the compiled IDO SDK classes + the native
   `.so` libraries into `dashboard-android` to make watch functions work immediately. Keep the
   option to replace high-value paths (health, notifications) with clean-room Kotlin over time.
2. **Health metrics first.** Because the lift brings the whole SDK, other functions
   (notifications, calls, DFU, watch faces) become "wire an existing `BLEManager` call" tasks,
   not new reverse-engineering. Health is wired end-to-end first.
3. **Private build only.** This build targets Fabian's own watch/private dashboard only and is not
   for public app-store, monetised, or general-user distribution. Revisit the proprietary SDK
   decision before any broader distribution. Any future monetised product should use a different,
   self-built watch path — it will **not** reuse this watch's proprietary SDK without a new decision.
   Bundling the proprietary blobs is therefore an accepted, bounded tradeoff for this private build.

### 2026-06-26 clarification — private engine, not a VeryFit app fork

Fabian's current goal is to stop spending time/tokens rebuilding the watch connection from
scratch and to use the extracted VeryFit APK as the fastest route to a working personal watch
bridge. This ADR supports that goal, but the implementation path is **not** to rebuild/rebrand
the whole 300 MB VeryFit app and delete features until it compiles.

The path is a targeted transplant:

- keep this app's Kotlin/Compose dashboard shell and dashboard API client,
- keep the IDO/VeryFit SDK jar/native libs quarantined behind `WatchEngine` / `IdoSdkWatchEngine`,
- use the decompiled VeryFit sources only as an oracle for SDK call order, callbacks, capability
  gates, bind/reconnect behavior, notifications/calls, and error handling,
- avoid importing VeryFit UI, ads, membership, marketplace, maps, analytics, vendor cloud, or
  third-party telemetry code into the app shell,
- send private watch data only to Fabian's configured dashboard origin unless a future feature is
  explicitly approved.

The working plan for this clarified goal is
[`docs/plans/veryfit-private-engine-workflow.md`](../plans/veryfit-private-engine-workflow.md).

## Architecture — the `WatchEngine` quarantine

The proprietary SDK sits behind a single interface so "clean-room later" is a swap, not a rewrite,
and nothing else in the app imports `com.ido.*`:

```
UI / ViewModels / WorkManager sync  ─►  WatchEngine (our interface, our domain types)
                                          └── IdoSdkWatchEngine   (current private build; wraps BLEManager + SDK callbacks)
                                              [future clean-room replacement can be reintroduced behind the same interface]
```

- `IdoSdkWatchEngine` is the **only** file permitted to import `com.ido.*` / `com.veryfit.*`.
- The earlier clean-room work is preserved in docs/git history and the `pre-ido-sdk-baseline` tag as protocol evidence. It is not an active runtime stack in the current app tree after the single-watch-stack cleanup.
- The `WatchEngine` interface remains the seam for a future clean-room replacement if that becomes worth rebuilding later.

## What is vendored

- `app/libs/ido-watch-sdk.jar` — compiled classes for `com.ido.ble.**`, `com.veryfit.multi.**`,
  `com.ido.common.**` and required helpers (extracted from the APK via dex2jar; the obfuscated
  decompiled Java cannot be recompiled, so JNI/class names are preserved by lifting compiled
  classes). Excludes app UI, ads, the real AMap/autonavi SDKs, Firebase, TikTok/Facebook,
  analytics, and their manifest auto-init components. The jar still contains some dead `com.ido.map.*`
  facade classes from the IDO SDK; they are not referenced by app-owned code and the backing map
  SDKs are absent, so treat them as inert baggage rather than a supported map feature.
- `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/` — `libVeryFitMulti.so`, `libprotocol.so`
  (plus `libsqlcipher.so` only if DB encryption is enabled; SiFli libs only when DFU/watch-face
  phases arrive).

## Local SDK storage policy

- The private dashboard engine sets `InitParam.isSaveDeviceDataToDB=false` so decoded health records are delivered through sync callbacks but not retained in the SDK greenDAO database. The app does not query the SDK DB.
- `isEncryptedDBData` remains false unless SQLCipher dependency/native libs are deliberately added and verified; the current vendored libs do not include SQLCipher.
- `isEncryptedSPData=true` enables the SDK's SharedPreferences string encryption. For existing private installs, clear app data and re-bind if legacy plaintext SDK prefs cause read/decrypt issues.

## Consequences

- **Positive:** full watch functionality fast; typed health data with no per-byte decoding;
  clean upgrade path to independence via the `WatchEngine` boundary.
- **Negative / accepted:** proprietary, obfuscated code + native blobs in the repo; bounded
  transitive OSS deps (greenDAO/gson/EventBus); brittleness across SDK versions; release builds
  must keep the JNI-reached packages un-shrunk/un-renamed (proguard keep rules).
- **Policy:** this overrides the clean-room mandate in `CLAUDE.md` **only** for the private build,
  by explicit decision recorded here. Revisit before any distribution.

## Maps

AMap/autonavi (Chinese maps) are dropped entirely. GPS health data (lat/lng track points) can
still sync as raw data without a renderer; map rendering, if wanted later, uses a non-Chinese
tile provider on the dashboard side.
