# Architecture

A small, layered, offline-first Android client. The guiding constraint from the
product plan: *see today's plan and act on it in under 5 seconds from the phone.*
That drives an offline-first cache and a contract isolated behind a repository.

```
┌────────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose)            Widget (Glance)                  │
│  Today / Capture / Settings      TodayWidget + action callbacks  │
│  ViewModels (StateFlow)                                          │
└───────────────┬───────────────────────────┬─────────────────────┘
                │                            │
                ▼                            ▼
        ┌───────────────────────────────────────────┐
        │ DashboardRepository                        │
        │  offline-first reads, mutation = replace   │
        └───────┬───────────────────────┬────────────┘
                │                        │
        ┌───────▼────────┐      ┌────────▼─────────┐
        │ DashboardApi   │      │ TodayCache       │
        │  Client (iface)│      │  (iface)         │
        │  ├ Retrofit    │      │  ├ Room          │
        │  └ Fake        │      │  └ InMemory      │
        └───────┬────────┘      └──────────────────┘
                │
        ┌───────▼────────┐   ┌──────────────────────┐
        │ DashboardService│   │ SettingsStore (iface)│
        │  (Retrofit)     │   │  └ EncryptedSharedPrefs│
        └─────────────────┘   └──────────────────────┘

  WorkManager: RefreshWorker (15-min periodic) → repository.refreshToday()
               → cache warm → TodayWidget.updateAll()
```

## Layers

- **`domain/model`** — plain Kotlin types (`TodayPayload`, `Readiness`,
  `FocusBlock`, `Habit`, `MainAction`, `WidgetAction`, `QuotePayload`,
  `CaptureResult`). No Android/network/serialization deps. The shared currency
  between UI, widget, and cache.

- **`data/api`** — the contract seam.
  - `DashboardApiClient` (interface): the entire server surface in domain terms.
  - `RetrofitDashboardApiClient` + `DashboardService` + `ApiClientFactory`: the
    live HTTPS implementation (Retrofit + OkHttp + kotlinx.serialization).
  - `FakeDashboardApiClient`: in-memory, deterministic, used for previews,
    pre-setup launch, and tests. Mutations behave like the server (return the
    fresh Today), so UI exercises the same code path as live.
  - `dto/` + `Mappers.kt`: `@Serializable` wire DTOs and pure DTO↔domain
    mappers — the unit-tested boundary where the wire contract is enforced.
  - `AuthInterceptor`: injects `Authorization: Bearer <token>` per request.

- **`data/cache`** — `TodayCache` (interface) with a Room implementation
  (single-row JSON snapshot) and an in-memory one. The Today payload is stored
  as a serialized DTO blob, so the table schema is stable across contract
  changes.

- **`data/settings`** — `SettingsStore` (interface) +
  `SecureSettingsStore` (EncryptedSharedPreferences). The device token is stored
  encrypted, never logged, never read back into UI state (only presence shown).

- **`data/repository`** — `DashboardRepository`: the single entry point. Reads
  serve cache then refresh; mutations replace the cache with the server's fresh
  Today; all network failures degrade rather than crash.

- **`ui/`** — Compose screens + ViewModels (`StateFlow`-driven, stateless
  composables with `@Preview`s fed by `FakeData`).

- **`widget/`** — Glance `TodayWidget`, its receiver, and `ActionCallback`s for
  habit toggle / focus start. Renders from cache only (never blocks on network).

- **`work/`** — `RefreshWorker` (CoroutineWorker) + `RefreshScheduler`.

- **`di/ServiceLocator`** — hand-rolled DI (the graph is tiny). Builds the live
  client from current settings, or the fake client when unconfigured.

## Key decisions

- **Contract behind an interface.** Nothing above `data/api` imports Retrofit.
  Swapping Fake↔live is a one-line change; the contract can be tested without a
  network or device.
- **Mutation = replace, not patch.** The server returns the full fresh Today on
  every mutation, so the client never reconciles partial state.
- **Offline-first.** Cache is painted first everywhere (app + widget); the
  network refresh updates it in the background.
- **Token security.** Encrypted at rest (Keystore-backed), redacted from OkHttp
  logs, excluded from backup, write-only in the UI.
- **Forward compatibility.** Lenient JSON (`ignoreUnknownKeys`) + nullable DTOs +
  `UNKNOWN` enum fallbacks, so a newer server doesn't break an older app.

## Not yet implemented (intentional V1 cuts)

- A dedicated Tasks list screen (the plan allows trimming to Today/Capture/
  Settings for V1; `mainAction`/`focusBlock` cover the task surface for now).
- Rendering the lock-screen `quote` (endpoint + model are wired; no UI surface).
- Using the focus `session.fireAt` to run an in-app/in-widget countdown.
- A real device-token mint/revoke flow in-app (done on the dashboard web UI;
  Settings documents it and accepts the pasted token).
