# CLAUDE.md — Android phone ADB + VeryFit BLE development rules

## Purpose

Use this as the working `CLAUDE.md` guidance for Claude Code sessions on the Android watch/BLE integration. The current direction is to use Fabian's real Android phone over ADB Wi-Fi rather than an emulator. Emulator-only assumptions are not useful for BLE tracing, GATT behavior, pairing, notification routing, or VeryFit comparison work.

## Project context

- Dashboard Android worktree: `/home/apolytus/workspace/worktrees/phone-integration-refinement/dashboard-android`
- Main dashboard repo: `/home/apolytus/workspace/worktrees/phone-integration-refinement/dashboard`
- VeryFit APK/reference tree: `/home/apolytus/workspace/veryfit-breakdown/` — refer to this for implementation; the official app already has a working Active 4 Pro BLE data collection system
- VeryFit package/version researched: `com.watch.life` v3.4.0.
- Target watch: Kogan Active 4 Pro / IDO-style VeryFit protocol family.
- Current direction: Fabian-private Kotlin dashboard app using the vendored IDO/VeryFit SDK as a quarantined watch engine behind `WatchEngine` / `IdoSdkWatchEngine`. Use VeryFit/APK/captures/decompiled sources as workflow documentation and SDK-call oracle; do **not** rebuild/rebrand the full VeryFit app, and do not import VeryFit UI/ads/cloud/branding into the app shell.

## Non-negotiable safety rules

- Do not upload contacts, GPS tracks, raw private health payloads, or full BLE dumps to model-facing systems by default.
- Treat raw BLE logs as developer-only and short-retention unless Fabian says otherwise.
- Do not assume a metric is supported just because the VeryFit APK has a model class. Label confidence as:
  - APK model/schema only
  - native/API-boundary ID discovered
  - capture-observed wire frame
  - watch-verified decoded value

## Architecture rules

Use clear Android boundaries. Prefer one of these patterns consistently:

- MVVM
- MVI
- clean architecture boundaries

Required separation:

- UI/composables: render state and emit events only.
- ViewModel/presenter layer: owns screen state, user intents, lifecycle-aware orchestration.
- BLE/data layer: owns Android Bluetooth APIs, GATT queueing, scan/connect/reconnect, service discovery, notification setup, writes, raw packet logging, and protocol status events.
- Protocol layer: owns frame parsing, command construction, checksums, reassembly, confidence labels, and typed result conversion.
- Repository/use-case layer: mediates persistence/upload decisions and keeps privacy policy out of UI widgets.

Do not put BluetoothGatt callback logic directly in composables.
Do not mix VeryFit reverse-engineering notes with production business logic without an explicit abstraction.

## UI conventions

Use Jetpack Compose conventions:

- State flows down; events flow up.
- Hoist state out of reusable composables.
- Keep composables small and previewable.
- Provide previews for non-trivial UI states when practical:
  - disconnected
  - scanning
  - connecting
  - connected/service discovered
  - notifications enabled
  - write failed
  - RX/logs available
- Separate UI log rendering from the BLE logger implementation.
- If adding tester-facing BLE diagnostics, include a one-tap “Copy Logs” action beside “Clear Log”; disable it when the log is empty.

## Dependency injection rules

Use only the project's established dependency approach. Acceptable options are:

- Hilt
- Koin
- the existing project service-locator pattern

Do not introduce a new DI framework or ad-hoc global singleton graph without Fabian's approval.
Android platform dependencies such as BluetoothManager, BluetoothAdapter, Context, and coroutine scopes should be injected/wrapped so BLE code can be tested without a live phone where practical.

## Testing rules

Use the right test type for the job.

Local JVM tests:

- Use for protocol parsing, frame summaries, checksum/CRC helpers, reassembly, command builders, privacy filters, and pure ViewModel state reducers.
- Expected command from the Android project root:

```bash
./gradlew testDebugUnitTest
```

AndroidX instrumented/device tests:

- Use for Android framework behavior, permissions, Bluetooth wrappers, Compose UI behavior requiring an Android runtime, and flows that need the connected phone.
- Expected command from the Android project root with the real phone connected over ADB:

```bash
./gradlew connectedDebugAndroidTest
```

Build/static verification:

```bash
./gradlew assembleDebug
./gradlew lintDebug
```

If the project has module-specific tasks, prefer the narrowest equivalent task and document the exact command/result in the handoff.

## ADB Wi-Fi phone workflow

Prefer the real phone over emulator for BLE work.

Discovery and connection:

```bash
adb devices -l
adb tcpip 5555
adb shell ip addr show wlan0
adb connect PHONE_IP:5555
adb devices -l
```

If USB is unavailable and the phone already has wireless debugging enabled, use the Android wireless debugging pairing flow and then verify with:

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

Install/run/debug from the Android project root:

```bash
./gradlew installDebug
adb shell am force-stop dev.jaredhq.dashboardandroid
adb shell monkey -p dev.jaredhq.dashboardandroid 1
```

Use the real package name from the manifest if it differs from `dev.jaredhq.dashboardandroid`.

### Fresh APK deployment rule

Before Claude claims that it tested behavior on Fabian's phone, Claude must deploy the current code to the phone through Android Studio/Gradle/ADB. The already-installed APK does not update automatically when code changes.

Acceptable fresh-deploy paths:

- Android Studio: use Run/Debug with the connected physical phone selected. This rebuilds and deploys the current variant before launch.
- Gradle/ADB from the Android project root:

```bash
./gradlew installDebug
adb shell am force-stop dev.jaredhq.dashboardandroid
adb shell monkey -p dev.jaredhq.dashboardandroid 1
```

If `installDebug` is unavailable or module-specific tasks are required, use the narrowest equivalent install task, for example `./gradlew :app:installDebug`, and record the exact task used.

Do not test BLE/app behavior against a stale APK. In every handoff that mentions phone testing, include the deploy command or Android Studio Run/Debug action used, the result, and the package/version actually launched.

Logcat workflow:

```bash
adb logcat -c
adb logcat | grep -i -E 'jared|ble|bluetooth|gatt|0af0|0af6|0af7|0af2|veryfit|ido'
```

For focused capture windows:

```bash
adb shell dumpsys bluetooth_manager
adb shell settings get global bluetooth_disabled_profiles
adb logcat -d > /tmp/dashboard-android-ble-logcat.txt
```

When testing BLE, close/force-stop VeryFit first because the watch may only tolerate one active BLE owner:

```bash
adb shell am force-stop com.watch.life
```

If connection suddenly regresses after firmware updates or VeryFit use, suspect stale bond/cache/ownership before changing protocol constants:

- force-stop VeryFit
- toggle Bluetooth
- forget/re-pair the watch if needed
- scan broadly rather than filtering only on service UUID
- log all discovered services/characteristics/properties

## Tooling rules

Preferred tools:

- `adb`
- `./gradlew`
- `adb logcat`
- Frida only from documented project paths/scripts
- Bumble only from documented project paths/scripts
- APK research artifacts under documented workspace paths

Do not invent new tool locations. If a script path is unknown, search the project/workspace first and document what was found.

Do not use emulator-only BLE conclusions as implementation evidence. BLE acceptance requires a real phone + target watch or a clearly labeled capture/APK-only finding.

## VeryFit/APK context Claude should use

Known VeryFit/IDO BLE service context:

```text
Service: 00000af0-0000-1000-8000-00805f9b34fb
Primary write characteristic: 00000af6-0000-1000-8000-00805f9b34fb
Primary notify characteristic: 00000af7-0000-1000-8000-00805f9b34fb
Secondary notify characteristic: 00000af2-0000-1000-8000-00805f9b34fb
Additional/encrypt/background characteristic: 00000af8-0000-1000-8000-00805f9b34fb
Possible firmware-dependent notify characteristic: 00000af1-0000-1000-8000-00805f9b34fb
CCCD: 00002902-0000-1000-8000-00805f9b34fb
Observed MTU: 247 effective, about 244 byte payload max
```

VeryFit APK research found:

- IDO SDK packages under `com.ido.ble.*`
- connection code in `com.ido.ble.bluetooth.connect.BaseConnect`
- UUID constants in `com.ido.ble.bluetooth.utils.frost`
- notification helpers in `com.ido.ble.bluetooth.utils.acorn`
- native protocol boundary at `com.veryfit.multi.nativeprotocol.Protocol`
- native library `libVeryFitMulti.so`
- Java/native sync entry points such as:
  - `ReceiveDatafromBle(byte[])`
  - `CallBackWriteDataToBle(byte[])`
  - `WriteJsonData(byte[], int)`
  - `StartSyncHealthData()`
  - `startSyncActivityData()`
  - `startSyncGpsData()`
  - `SetSyncHealthOffset(type, offset)`

Important implication: VeryFit is not simply writing JSON directly to BLE. Java-facing JSON/model callbacks often sit at the app/native boundary; the BLE wire protocol remains binary until proven by capture.

## Product direction — Fabian-private build first

This watch integration is currently for Fabian's private dashboard, not the monetized/general-user product. Optimize for Fabian's Active 4 Pro, Fabian's phone, and private dashboard sync before commercial polish.

Current priority:

- reliable direct BLE for Fabian's watch
- useful private trend data over time
- clear debug logs and copyable evidence
- privacy-conscious sync into Fabian's own dashboard
- Jared/AI pattern support once the data is trustworthy enough

Do not overbuild this phase for broad smartwatch compatibility, consumer onboarding, app-store polish, enterprise health compliance scaffolding, or generic monetized-product abstractions. If monetization happens later, treat it as a separate product fork: standard health integrations or a supported-compatible-smartwatch model.

## BLE implementation strategy

> **⚠ Scoped exception in force (2026-06-24, ADR 0001).** For the **private build**, Fabian
> explicitly approved **vendoring the IDO/VeryFit SDK** (`app/libs/ido-watch-sdk.jar` +
> `jniLibs/*.so`) as a runtime dependency — the override the "Reference-guided backend rule"
> below reserves for his approval. The SDK is quarantined behind the `WatchEngine` interface
> (`IdoSdkWatchEngine` is the only file that may import `com.ido.*` / `com.veryfit.*`); the
> clean-room/source-clean rules below still govern **all app-owned code**. A future clean-room
> engine may be reintroduced behind `WatchEngine`, but the current active tree is the private
> SDK-backed `IdoSdkWatchEngine` path. See [`docs/adr/0001-vendor-ido-sdk.md`](docs/adr/0001-vendor-ido-sdk.md).
> Revisit before any distribution — this approval is private-build-only.

### Reference-guided backend rule

The project direction is **not** to copy proprietary backend source code from VeryFit or other APKs into this repository. Reference apps may be inspected as architecture/protocol documentation only.

Allowed:

- Use APK/reference-app findings to understand proven BLE/GATT sequencing, UUIDs, native/API-boundary behavior, permissions, and failure handling.
- Reimplement those ideas cleanly in this app with our own source code.
- Keep the frontend in Kotlin/Jetpack Compose with ViewModel-driven state.
- Refactor the BLE backend toward a simple Java-style state machine and serialized GATT operation queue if that improves reliability, regardless of whether the source file is Kotlin or Java.
- Use Java/Kotlin interop if there is a clear maintainability reason, but do not switch languages just because a reference app used Java.

Not allowed unless Fabian explicitly approves the legal/maintenance tradeoff:

- Copy/paste decompiled proprietary Java/Kotlin source into the repo.
- Treat VeryFit native libraries or obfuscated backend code as runtime dependencies.
- Commit copied APK internals, secrets, private payload dumps, or black-box code that cannot be explained and maintained.

Stability comes from BLE architecture, not from Java itself. Prioritize:

- broad scan fallback before service-specific filtering assumptions
- one serialized GATT operation queue
- explicit connection states such as `Disconnected`, `Scanning`, `Connecting`, `DiscoveringServices`, `InitializingGatt`, `NotificationsEnabling`, `Ready`, and `Error`
- no app/debug writes until state is `Ready`
- request MTU before protocol setup when appropriate
- read/setup steps such as `0x0AF8` before notify/write if the watch/reference sequence requires it
- serial notification/indication setup for `0x0AF7`, `0x0AF2`, and `0x0AF1` when present and notify/indicate-capable
- correct CCCD value: notification vs indication
- clear logging of every read/write/descriptor return value and callback status
- privacy-conscious raw packet logging that is debug-gated/redacted before broad health/activity sync

First milestone is infrastructure, not full health decoding:

1. Broad scan with conservative name/address filtering; do not require service UUID in advertisements.
2. Connect GATT on the real phone.
3. Request MTU.
4. Discover services.
5. Confirm service `0x0AF0`.
6. Enumerate and log every characteristic and property.
7. Enable notify/indicate-capable protocol characteristics serially, especially `0x0AF7`, `0x0AF2`, and firmware-dependent `0x0AF1` if it has notify/indicate properties.
8. Do not let a characteristic without CCCD stall the chain.
9. Queue all GATT operations. Never fire descriptor writes/characteristic writes concurrently.
10. Write only capture-gated probe commands to `0x0AF6`.
11. Log TX, write-complete status, RX characteristic UUID, raw bytes, and frame summary separately.
12. Upload/coalesce dashboard status after RX/probe completion, not only at initial connected state.

Do not implement broad metric sync until scan/connect/service discovery/notify/write/RX are verified on the real phone.

## Debugging heuristics

If scan fails:

- remove service UUID scan filter
- check phone Bluetooth state and permissions
- ask Fabian for exact advertised name/address from a BLE scanner if needed

If connect succeeds but service is missing:

- log all services
- force-stop VeryFit
- toggle Bluetooth
- consider stale bond/cache after firmware changes

If writes succeed but RX is empty:

- confirm notifications were enabled serially and descriptor writes succeeded
- enable all notify-capable protocol characteristics, not only AF7
- record which RX characteristic produced data
- request focused official-app btsnoop capture before guessing new frame constants

If AF7 emits short repeated packets such as `AD 01 32 00 00 00`:

- treat them as possible ACK/status packets, not decoded health data
- keep probing only with capture-backed commands
- inspect AF2/AF1 traffic before concluding no useful data exists

## Expected Claude handoff format

Every BLE implementation or tracing session should report:

- phone model and Android version from ADB
- ADB connection mode and serial
- app package tested
- exact Gradle commands run and results
- whether VeryFit was force-stopped
- scan result/name/address used
- discovered services/characteristics/properties
- MTU result
- notification enable order and descriptor write statuses
- TX frames and write-complete statuses
- RX frames grouped by characteristic UUID
- privacy-sensitive payloads redacted
- confidence level for every protocol claim
- next smallest testable step