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
  decides task/note/event) or a direct task (`/capture`). Reachable in one tap
  from the **fast-capture widget** or a notification (deep link).
- **Two home-screen widgets:**
  - **Today** — headline, focus block (▶ start), habits (✓ toggle), plus a
    **＋ Capture** deep link. Brand-styled (warm surface, legible on the dark tile).
  - **Fast capture** — a compact tile whose **＋ Capture** / **Today** buttons
    deep-link straight into the app, for the lowest-friction capture path.
- **Notifications bridge:** a background worker mirrors the dashboard's
  notifications feed (`/notifications`) onto the phone — today's **events** and
  **due/overdue deadlines** — plus a once-a-day, lock-screen-visible **daily
  quote** (`/quote`). De-duped so it never spams. See
  [Notifications](#notifications).
- **Settings screen:** dashboard base URL + per-device token entry, a connection
  test, and guidance to mint/revoke a token in the dashboard's Settings →
  Devices.
- **Brand identity:** the app, widgets, and launcher icon use the dashboard's
  own palette — warm charcoal base, terracotta accent, heather-violet secondary —
  and its constellation mark, so the companion reads as the same product.
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
4. **Add a widget** to your home screen from the widget picker — **Dashboard →
   Today** and/or **Dashboard → Fast capture**.
5. **Allow notifications** when prompted on first launch (Android 13+) so the
   reminders bridge and daily quote can post. You can tune the two channels
   ("Reminders", "Daily quote") later in the system app-settings.

Until a URL/token is set, the app runs against built-in **sample data** (the
`FakeDashboardApiClient`) so every screen is navigable offline.

### Tailscale / HTTPS notes

The base URL should be your dashboard's HTTPS origin reachable from the phone —
typically the Tailscale MagicDNS HTTPS URL (e.g.
`https://dashboard.your-tailnet.ts.net`). Tailscale Serve/Funnel issues a valid
Let's Encrypt cert for `*.ts.net`, so standard HTTPS validation works with **no
custom certificate handling** — the app ships no cleartext/trust-all config.

> **The phone must be on the tailnet.** A MagicDNS `*.ts.net` name only resolves
> when the Tailscale app is installed and connected on the phone. If
> *Test connection* says "unreachable", check Tailscale is up first. When the
> dashboard later moves to a public HTTPS origin, only the base URL changes —
> the client needs no code change (see [Future: public deployment](#future-public-deployment)).

### Future: public deployment

The networking layer is origin-agnostic: `ApiClientFactory.normalizeBaseUrl`
accepts any valid `https://` (or `http://` for local dev) origin and the bearer
token works the same way regardless of how the dashboard is reached. To point the
same app at a monetised/public backend, the user just changes the **Dashboard
URL** in Settings — no rebuild.

## Notifications

The dashboard stays the source of truth; the phone is a display/scheduling
surface:

- A periodic `NotificationWorker` (every ~3h, network-constrained) polls
  `GET /api/widget/v1/notifications` and posts native notifications for **timed
  events** and **due/overdue deadlines** on a **Reminders** channel.
- It also polls `GET /api/widget/v1/quote` and posts the **daily quote** once per
  day on a low-importance **Daily quote** channel, with `VISIBILITY_PUBLIC` so the
  full line shows on the lock screen.
- Posting is idempotent (`NotificationState`): each reminder fires at most once
  per day, the quote at most once per date — running several times a day never
  duplicates.
- The morning brief / reminder *scheduling* is still owned by the dashboard's
  server-side **web push** (the `/api/cron/*` jobs); this bridge intentionally
  does not re-post the brief.

**Lock-screen note (platform limitation).** Android does not allow third-party
apps to place true widgets on the lock screen or to draw arbitrary lock-screen
content. The reliable native equivalent — and what this app does — is a
lock-screen-**visible** daily-quote notification (and the home-screen quote can
be surfaced in-app/in a widget). No risky wallpaper or overlay hacks are used.

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
  tolerance, forward-compat (unknown fields ignored), DTO round-trip, and the
  enriched `/focus/start`, `/capture`, and `/chat`-fallback shapes.
- `NotificationsMappingTest` — decodes the server's real `/notifications` JSON
  (incl. the reserved-word `when` field and a future field) and asserts the
  DTO→domain mapping, unknown `kind`/`priority` tolerance, and an empty feed.
- `RepositoryTest` — offline-first refresh + cache, the "mutation returns the
  fresh Today, replace state" behavior, and the read-only `testConnection()`
  probe (success without writing cache; auth → AuthFailed; network → Unreachable;
  quote-down still Connected), against in-memory fakes/doubles.
- `BaseUrlNormalizationTest` — origin normalization, path/query stripping, scheme
  validation, idempotence.
- `DeviceTokenFormatTest` — the non-binding `dwtk_` token format hint.

### Connection behavior

- **Settings → Test connection** is read-only: it validates/normalizes the URL,
  saves it, then probes `GET /today` (+ `/quote`) without mutating the server or
  the local cache, and shows an actionable result (connected / auth / unreachable).
- **Today** refreshes from `/api/widget/v1/today` on every resume — first show,
  returning to the tab, and the app coming back to the foreground — so a capture
  done elsewhere or a change on another device shows up. After an in-app capture/
  chat the home-screen widget is refreshed from the freshly-cached Today as well.

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

The **Phase 12 additions** (fast-capture widget + deep links, notifications
bridge + daily quote, brand theme/icon) were likewise written without the Android
toolchain in this environment — **not compiled or run here.** They follow the
existing layering and APIs (Glance, WorkManager, `NotificationCompat`) and the
new contract has a JVM test (`NotificationsMappingTest`), but the same gate
applies.

**Before relying on it:** open in Android Studio, sync, run
`./gradlew testDebugUnitTest`, then `assembleDebug`, and fix any version-skew the
local SDK surfaces (most likely AGP/Kotlin/Compose-BOM alignment in
`gradle/libs.versions.toml`).

## Manual test plan

After `assembleDebug` + install on a device, with the dashboard reachable over
the Tailscale HTTPS URL:

1. **Connectivity / Tailscale.** Ensure the Tailscale app is connected on the
   phone. Settings → enter the `*.ts.net` URL + an `actions`-scope token → *Test
   connection* → expect "Connected — Today loaded for <date>". Toggle Tailscale
   off → *Test connection* → expect a clear "unreachable" message (not a crash).
   Paste a bad/`read`-only token → expect the auth-specific message.
2. **Fast capture / chat.** Capture tab → type "Remind me to finish physics
   revision tomorrow" with **Assistant** selected → *Capture* → expect a one-line
   assistant reply; verify the task/reminder appears in the dashboard web app.
   Repeat with **Task only** → expect "Saved as a task (#id)".
3. **Fast-capture widget.** Add the **Fast capture** widget → tap **＋ Capture** →
   app opens directly on the Capture screen (cold start *and* while already
   running). Tap **Today** → opens the Today tab.
4. **Today widget.** Add the **Today** widget → ✓ a habit → it flips and the app's
   Today reflects it on next resume. ▶ start the focus block. Tap **＋ Capture**.
5. **Today overview.** Today tab → pull data shows headline, readiness, main
   action, focus block, body/reset tiles, habits, warnings; *Refresh* re-pulls.
6. **Notifications.** Grant the notification permission. To exercise without
   waiting ~3h, trigger the worker from Android Studio (App Inspection →
   Background Task Inspector → run `notifications-bridge`) — or temporarily lower
   the period. Expect: a **Daily quote** notification (full text on the lock
   screen) once per day, and **Reminders** for today's events / due deadlines,
   not duplicated on a second run.
7. **Emulator vs device.** All of the above work on an emulator **except**
   reaching a Tailscale `*.ts.net` host (the emulator isn't on your tailnet unless
   Tailscale runs on the host and you use the host's tailnet via `10.0.2.2`/a
   reverse proxy). Easiest: test connectivity on a physical device with Tailscale;
   use the emulator with `FakeDashboardApiClient` (no URL set) or a `http://10.0.2.2`
   local dev server for UI/flow work.

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
