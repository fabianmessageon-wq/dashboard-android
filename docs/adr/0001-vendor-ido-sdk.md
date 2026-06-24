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
3. **Private forever.** This build targets only Fabian's own watch and is never distributed.
   Any future monetised product will use a different, self-built watch — it will **not** reuse
   this watch's proprietary SDK. Bundling the proprietary blobs is therefore an accepted,
   bounded tradeoff for this private build.

## Architecture — the `WatchEngine` quarantine

The proprietary SDK sits behind a single interface so "clean-room later" is a swap, not a rewrite,
and nothing else in the app imports `com.ido.*`:

```
UI / ViewModels / WorkManager sync  ─►  WatchEngine (our interface, our domain types)
                                          ├── IdoSdkWatchEngine   (wraps BLEManager + SDK callbacks)
                                          └── CleanRoomWatchEngine (existing WatchBleManager/WatchProtocol, retained)
```

- `IdoSdkWatchEngine` is the **only** file permitted to import `com.ido.*` / `com.veryfit.*`.
- The existing clean-room code (`WatchBleManager`, `WatchProtocol`, `WatchActivityReassembler`,
  the watch-verified `02 04`/`02 01`/`02 A7` parsers, activity summary decode) is **kept** as the
  long-term independent path and as an oracle cross-check — not deleted.
- A `BuildConfig`/feature flag selects the active engine (default: IDO, for the private build).

## What is vendored

- `app/libs/ido-watch-sdk.jar` — compiled classes for `com.ido.ble.**`, `com.veryfit.multi.**`,
  `com.ido.common.**` and required helpers (extracted from the APK via dex2jar; the obfuscated
  decompiled Java cannot be recompiled, so JNI/class names are preserved by lifting compiled
  classes). Excludes app UI, ads, AMap/autonavi maps, Firebase, TikTok/Facebook, analytics.
- `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/` — `libVeryFitMulti.so`, `libprotocol.so`
  (plus `libsqlcipher.so` only if DB encryption is enabled; SiFli libs only when DFU/watch-face
  phases arrive).

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
