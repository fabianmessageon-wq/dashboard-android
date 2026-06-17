# Dashboard widget API contract (`/api/widget/v1`)

This is the contract the Android client consumes. It is derived from the
dashboard server source as merged into `main` (PR #86, commit `a735a18`):

- `src/lib/intelligence/widgetPayload.ts` — `toWidgetPayload` (the Today shape)
- `src/app/api/widget/v1/**/route.ts` — the six route handlers
- `src/lib/auth/device.ts`, `src/lib/auth/index.ts` — per-device token auth

> **Versioning.** The path is pinned at `/v1/` and every Today payload carries a
> `version` field (currently `1`). A mobile client outlives a web UI, so treat
> any field change as a breaking change: bump the version and add a `/v2`.

## Auth

Every endpoint authenticates via an `Authorization: Bearer <token>` header (or,
for the web app, the session cookie). Tokens are minted per device in the
dashboard's **Settings → Devices** page; only a SHA-256 hash is stored
server-side, and the raw value (prefix `dwtk_`) is shown once.

Scopes (higher rank satisfies lower):

| Scope     | Grants                                             |
|-----------|----------------------------------------------------|
| `read`    | `GET /today`, `GET /quote`                         |
| `actions` | everything in `read` **plus** all mutations        |

Failures: `401` (missing/invalid/revoked token), `403` (token scope too low).
Error bodies are `{ "error": "..." }`.

## `GET /api/widget/v1/today`  — scope: `read`

Returns the versioned **Today** payload (the `WidgetTodayPayload`):

```jsonc
{
  "version": 1,
  "date": "2026-06-17",                 // YYYY-MM-DD, effective tz
  "generatedAt": "2026-06-17T07:30:00.000Z",
  "headline": "Deep work on the proposal",
  "recoveryMode": false,
  "readiness": { "score": 72, "band": "high" },   // band: low | moderate | high
  "mainAction": {                        // or null
    "title": "Proposal",
    "detail": "Due Thursday",            // nullable
    "href": "/tasks",
    "taskId": 12
  },
  "focusBlock": {                        // or null; labels are tz-local HH:mm
    "startLabel": "09:30",
    "endLabel": "11:00",
    "taskId": 12                         // nullable
  },
  "bodyAction": { "title": "Move your body", "detail": null, "href": "/workouts", "state": "do" },
  "resetAction": { "title": "Reflect", "detail": "Two minutes", "href": "/reflection", "state": "do" },
  "habits": [ { "id": 1, "title": "Read", "doneToday": false } ],
  "habitsRemaining": 2,
  "warnings": [ "3 tasks due today" ]
}
```

`state` (body/reset action) is one of `do | done | rest`. No `Date` objects or
epoch seconds appear on the wire — the server formats focus times to tz-local
`HH:mm`.

## `GET /api/widget/v1/quote`  — scope: `read`

A deliberately separate, never-blocking lock-screen quote (`WidgetQuote`):

```jsonc
{
  "version": 1,
  "date": "2026-06-17",
  "text": "The work is the reward…",
  "source": { "title": "On Craft", "slug": "on-craft" }   // or null (built-in fallback)
}
```

Always `200` — if the knowledge base is unreachable the server returns a
built-in fallback line rather than an error.

## Mutations — each returns the **fresh full Today payload**

Every mutation re-computes and returns the entire Today payload so the client
replaces local state with one call (no patching). Mutations are scope `actions`.

### `POST /api/widget/v1/habits/{id}/toggle`
No body. Toggles the habit's done-state for today. `404` if the habit isn't the
caller's. Response: the Today payload (with that habit flipped and
`habitsRemaining` adjusted).

### `POST /api/widget/v1/focus/start`
Optional body — both fields optional:
```jsonc
{ "taskId": 12, "durationMinutes": 25 }
```
Defaults to a 25-minute block and no task link. `400` on an invalid `taskId`.
Response (`201`): the Today payload **plus** a `session` object (with `fireAt`)
the client can use to run its own countdown. V1 ignores `session`.

### `POST /api/widget/v1/capture`
```jsonc
{ "title": "Buy milk" }
```
Deterministic, offline-safe: always creates a task. `400` if `title` is empty.
Response (`201`): the Today payload **plus** `createdTaskId`.

### `POST /api/widget/v1/chat`
```jsonc
{ "message": "Meeting with Sam Friday 2pm" }
```
Intelligent capture: the server's agent decides task vs note vs event vs
reminder and fires the right tool. **Provider-agnostic** — the client never
names a model; the active backend is whatever the user chose in dashboard
settings. When model calls are disabled it degrades to deterministic task
capture. `400` empty message, `502` on agent failure.

Response (`201`): the Today payload **plus**:
```jsonc
{
  "reply": "Added an event for Friday 2pm.",
  "actions": ["create_event"],          // tool names executed
  "pendingConfirmation": [],            // destructive tools awaiting confirm (assistant mode)
  "createdTaskId": 87,                  // present only on the task-fallback path
  "captureMode": "assistant"           // "assistant" | "task-fallback"
}
```

## Client mapping notes

- The Android DTOs (`data/api/dto/TodayDto.kt`) make every field nullable/
  defaulted and the JSON decoder uses `ignoreUnknownKeys = true`, so the server
  can add fields without breaking older app builds.
- Unknown enum strings (`band`, `state`, `captureMode`) map to an `UNKNOWN`
  variant rather than throwing.
- The capture/chat response is decoded as a flat object that inlines the Today
  fields (`CaptureResponseDto`), matching the server's `{ ...todayPayload, ... }`.
