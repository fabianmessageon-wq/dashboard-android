# Dashboard — Android companion

A native Android client (app + home-screen widget) for the self-hosted
[dashboard](https://github.com/fabianmessageon-wq) personal life dashboard. It
consumes the dashboard's versioned widget API (`/api/widget/v1`) so you can see
today's plan and act on it — toggle a habit, start a focus block, capture a
thought — in a few seconds from your phone.

This is **Phase 11's native client**, deliberately a separate repo from the
dashboard (the server owns the contract; this owns the phone). See the plan:
`docs/roadmap.md` Phase 11 in the dashboard repo.

- **Stack:** Kotlin · Jetpack Compose · Glance (widget) · WorkManager (refresh)
  · Room (offline cache) · Retrofit/OkHttp · kotlinx.serialization · DataStore /
  EncryptedSharedPreferences.
- **Min SDK:** 26 · **Target/Compile SDK:** 34 · **JDK:** 17.

## What it does (V1)

- **Today screen & widget:** headline / main action, recovery-mode flag, focus
  block (▶ start), habits (✓ toggle), readiness, warnings.
- **Capture screen:** quick capture via the assistant (`/chat`, the dashboard
  decides task/note/event) or a direct task (`/capture`).
- **Settings screen:** dashboard base URL + per-device token entry, a connection
  test, and guidance to mint/revoke a token in the dashboard's Settings →
  Devices.
- **Offline-first:** the last Today payload is cached (Room) and rendered
  instantly; a WorkManager job refreshes it every ~15 min and updates the widget.

## API contract

The server endpoints, payloads, scopes, and auth are documented in
[`docs/api-contract.md`](docs/api-contract.md). Architecture and layering are in
[`docs/architecture.md`](docs/architecture.md).

The contract was aligned against the dashboard server as merged into `main`
(PR #86 / commit `a735a18`): `src/lib/intelligence/widgetPayload.ts`,
`src/app/api/widget/v1/**`, and `src/lib/auth/device.ts`.

## Setup

1. **Open in Android Studio** (Koala / 2024.1+). Let it sync Gradle — it will
   download the Gradle distribution and dependencies.
2. **Run a device token** on the dashboard: open it in a browser → **Settings →
   Devices** → create a token with the **`actions`** scope (so habit toggle /
   focus / capture work). Copy the `dwtk_…` value (shown once).
3. **In the app → Settings:** enter your dashboard URL (your Tailscale HTTPS
   origin, e.g. `https://dashboard.your-tailnet.ts.net`) and paste the token.
   Tap **Test connection**.
4. **Add the widget** to your home screen from the widget picker ("Dashboard →
   Today").

Until a URL/token is set, the app runs against built-in **sample data** (the
`FakeDashboardApiClient`) so every screen is navigable offline.

### Tailscale / HTTPS notes

The base URL should be your dashboard's HTTPS origin reachable from the phone —
typically the Tailscale MagicDNS HTTPS URL. Plain-HTTP origins are not allowed by
default (no cleartext config is shipped); keep the dashboard on HTTPS.

## Bootstrap (Gradle wrapper)

This repo intentionally does **not** vendor the Gradle wrapper JAR
(`gradle/wrapper/gradle-wrapper.jar`) — binaries are kept out of source. The
wrapper properties pin the version. Generate the wrapper once:

```bash
# Requires a local Gradle (or just let Android Studio do it on first sync):
gradle wrapper --gradle-version 8.10.2
# then build/test with the wrapper:
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Android Studio generates the wrapper automatically on import, so most users can
skip this step.

## Tests

Pure-JVM unit tests run without a device or emulator:

```bash
./gradlew testDebugUnitTest
```

- `PayloadMappingTest` — decodes a sample of the server's real Today JSON and
  asserts the DTO→domain mapping (the contract regression test), unknown-enum
  tolerance, forward-compat (unknown fields ignored), and DTO round-trip.
- `RepositoryTest` — offline-first refresh + cache, and the "mutation returns the
  fresh Today, replace state" behavior, against the in-memory fakes.

## Verification status — IMPORTANT

This project was authored in an environment **without** the Android toolchain
(no JDK, no Gradle, no Android SDK). Therefore:

- ❌ **Not compiled.** `./gradlew assembleDebug` has **not** been run here.
- ❌ **Unit tests not executed.** `testDebugUnitTest` has not been run here.
- ❌ **Not run on a device/emulator.** The Glance widget and WorkManager paths
  are written against current APIs but have not been exercised at runtime.
- ✅ **Source/structure complete and self-consistent**, with version-catalogued
  dependencies and Compose `@Preview`s.
- ✅ **Contract verified against the dashboard server source**, not guessed.

**Before relying on it:** open in Android Studio, sync, run
`./gradlew testDebugUnitTest`, then `assembleDebug`, and fix any version-skew the
local SDK surfaces (most likely AGP/Kotlin/Compose-BOM alignment in
`gradle/libs.versions.toml`).

## Integration risks

- **Server availability/shape.** The client targets the contract in
  `docs/api-contract.md`. If the dashboard's payload changes, `PayloadMappingTest`
  and the lenient decoder localize the break to `data/api/dto`.
- **Auth/scope.** A `read`-scope token will 403 on mutations; the app surfaces an
  auth error and points you back to Settings.
- **Provider-agnostic chat.** `/chat` behavior depends on the dashboard's
  configured model provider and PKS consent; with model calls off it degrades to
  task capture (the app shows that).

## License

Personal project — no license granted yet.
