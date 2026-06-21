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

Every endpoint authenticates via an `Authorization: Bearer *** header (or,
for the web app, the session cookie). Tokens are minted per device in the
dashboard's **Settings → Devices** page; only a SHA-256 hash is stored
server-side, and the raw value (prefix `dwtk_`) is shown once.

Scopes (higher rank satisfies lower):

| Scope     | Grants                                             |
|-----------|----------------------------------------------------|
| `read`    | `GET /today`, `GET /quote`, `GET /notifications`   |
| `actions` | everything in `read` **plus** all mutations        |

Failures: `401` (missing/invalid/revoked token) with body `{ "error": "unauthorized" }`,
`403` (token scope too low) with body `{ "error": "forbidden" }`. The Android
client maps by status code (`ApiException.isAuthError` = 401/403), so it does not
depend on the body text.

**Connection test (client-side):** Settings → *Test connection* exercises this
surface read-only — `GET /today` (and `GET /quote` for a fuller signal) — via
`DashboardRepository.testConnection()`. It mutates neither the server nor the
local cache, and maps the outcome to `Connected` / `AuthFailed` / `Unreachable`.

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

### Additive: today's agenda + day shape (forward-compatible)

The dashboard is rolling out two **additive** fields on this payload so the
client can answer *"what is my day already committed to?"* at a glance. They are
purely optional: older payloads omit them, and the Android DTO defaults `agenda`
to `[]` and `daySummary` to `null`, so nothing breaks either way.

> **Field names are provisional.** The exact server keys are still being settled
> on the dashboard side. The client models the shape below and decodes leniently
> (`ignoreUnknownKeys`, every field optional/defaulted); if the server ships
> different names, only the DTO/mapper changes — domain/UI stay put. Treat the
> names here as the client's current assumption, not a frozen contract.

```jsonc
{
  // …all existing Today fields…
  "agenda": [                            // committed events/blocks, display order; [] when open/absent
    {
      "title": "Standup",
      "startLabel": "09:00",             // tz-local "HH:mm", or null (all-day/undated)
      "endLabel": "09:15",               // tz-local "HH:mm", or null
      "timeLabel": "09:00–09:15",        // optional pre-formatted span ("All day", etc.)
      "href": "/calendar",               // optional deep link
      "source": "Work",                  // optional calendar/source label
      "busy": true                       // true blocks focus time; false = tentative/free marker (default true)
    }
  ],
  "daySummary": {                          // or null when no day-summary data is sent
    "freeDay": false,
    "hasCalendarBlocks": true,
    "committedMinutes": 105,
    "freeMinutes": 315,
    "summary": "1h45m committed · ~5h free"   // optional server-formatted one-liner
  }
}
```

**Android handling:** mapped to `TodayPayload.agenda: List<TodayEvent>` and
`TodayPayload.daySummary: TodayDaySummary?`. The client derives an `isOpenDay`
signal (server's `freeDay`/`hasCalendarBlocks` when present, else "no busy
events"), surfaces a **Day plan** card (open vs blocked + next 2–3 events) above
the recommended task on the Today screen, and shows the next block or "Open day"
on the home-screen widget. `busy` defaults `true`, and a blank `summary` maps to
`null`.

### Additive: `relevantTasks` (multiple recommended tasks)

Today no longer has to be a single `mainAction`. The server now also emits a
short, ranked **`relevantTasks`** list — in-progress, overdue/due-today,
high-priority-goal, and slot-fitting tasks — so a phone can show a few useful
tasks, not just one. It is **additive and forward-compatible**: older payloads
omit it, and the Android DTO defaults it to `[]`. `mainAction === relevantTasks[0]`
when both are present, so a compact widget keeps using `mainAction` while the
Today screen can render the list.

```jsonc
{
  // …all existing Today fields…
  "mainAction": { "title": "Write proposal", "taskId": 12, "href": "/tasks", "detail": "high-priority goal" },
  "relevantTasks": [                       // ranked; [] when nothing is queued
    {
      "title": "Write proposal",
      "detail": "high-priority goal · fits a free slot",  // top reason(s); nullable
      "href": "/tasks",
      "taskId": 12,
      "score": 91,                         // 0–100; show the human `detail`, not the number
      "goalPriority": "high",              // "high" | "medium" | "low" | null
      "inProgress": false                  // true = actively being worked
    }
  ]
}
```

The projection is **privacy-safe**: it carries only the task title, a couple of
human reasons, and routing/identity — never task notes or other private content.

**Android handling:** map to `TodayPayload.relevantTasks: List<TodayTask>` (every
field nullable/defaulted, unknown `goalPriority` → `UNKNOWN`). The Today screen
shows the recommended-tasks list (in-progress first); the Glance widget stays
compact — primary action + next event or `+N more`, not five cards.

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

Auth still applies (`401`/`403` like any read endpoint). **After** auth, it
never 5xxs on a knowledge-base problem: if the notes index is unreachable the
server falls back to a built-in line and returns `200`. So it can fail to *auth*,
but a successful auth always yields a quote, never a server error.

## `GET /api/widget/v1/notifications`  — scope: `read`

The notifications/reminders feed the Android notification bridge polls (see
`src/lib/intelligence/notifications.ts`). A flat, versioned projection of today's
events, approaching/overdue deadlines, remaining habits, and the headline. Pure,
read-only, never throws on empty data (a quiet day returns `items: []`).

```jsonc
{
  "version": 1,
  "date": "2026-06-17",
  "generatedAt": "2026-06-17T07:30:00.000Z",
  "headline": "Deep work on the proposal",
  "items": [
    {
      "id": "deadline:task:12",         // stable, namespaced (de-dupe key)
      "kind": "deadline",               // headline | event | deadline | habit | warning
      "title": "Submit launch proposal",
      "detail": null,                   // nullable
      "timeLabel": "Due today",         // "9:00 AM" | "All day" | "Due in 2 days" | "Overdue (…)" | null
      "when": 1781654400,               // epoch SECONDS, or null for undated items
      "href": "/tasks",
      "priority": "high"                // high | normal | low
    }
  ],
  "counts": { "events": 1, "deadlines": 1, "habitsRemaining": 2 }
}
```

**Android handling:** decoded via `NotificationsPayloadDto → NotificationsPayload`
(lenient: unknown `kind`/`priority` → `UNKNOWN`; missing fields defaulted). The
`when` JSON field is a Kotlin keyword, mapped to `whenEpoch` with `@SerialName`.
The `NotificationWorker` surfaces only `event`/`deadline` items as native
notifications (the morning brief is delivered by the dashboard's server-side web
push; habit/warning items stay in the app/widget), de-duped per day via
`NotificationState`. See the README "Notifications" section.

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
Response (`201`): the Today payload **plus** a `session` object:
```jsonc
{ "session": { "id": 42, "fireAt": 1750000000 } }   // fireAt is epoch SECONDS
```
`fireAt` is the epoch-seconds end of the block, for a client-run countdown.
**Android handling:** decoded into `FocusStartResult(today, session)` and
preserved (the repository caches `today`); V1 doesn't surface the countdown yet.

### `POST /api/widget/v1/capture`
```jsonc
{ "title": "Buy milk" }
```
Deterministic, offline-safe: always creates a task. `400` if `title` is empty.
Response (`201`): the Today payload **plus** `createdTaskId`. Note there is **no**
`captureMode` field here (that's a `/chat` concept). **Android handling:** mapped
to `CaptureResult` with `mode = DIRECT` and the `createdTaskId` preserved.

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
  "pendingConfirmation": [],            // assistant mode only — ABSENT on the fallback path
  "createdTaskId": 87,                  // present only on the task-fallback path
  "captureMode": "assistant"           // "assistant" | "task-fallback"
}
```
`pendingConfirmation` is omitted entirely on the deterministic task-fallback
path (AI off); the Android DTO defaults it to an empty list so both shapes
decode. `createdTaskId` only appears on the fallback path.

## Client mapping notes

- The Android DTOs (`data/api/dto/TodayDto.kt`) make every field nullable/
  defaulted and the JSON decoder uses `ignoreUnknownKeys = true`, so the server
  can add fields without breaking older app builds.
- Unknown enum strings (`band`, `state`, `captureMode`) map to an `UNKNOWN`
  variant rather than throwing.
- The enriched responses are decoded as flat objects that inline the Today
  fields, matching the server's `{ ...todayPayload, ... }`: `CaptureResponseDto`
  for `/capture` and `/chat`, `FocusStartResponseDto` (adds `session`) for
  `/focus/start`. Each projects the Today portion via `toTodayDto()`.
- The interface returns rich types so nothing is silently dropped:
  `startFocus → FocusStartResult`, `capture → CaptureResult` (DIRECT mode),
  `chat → CaptureResult`.

