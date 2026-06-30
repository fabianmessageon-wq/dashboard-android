# Server-side watch cleanup — PROPOSAL ONLY (do not apply to the live VPS)

> Drafted 2026-06-29 for Fabian to review. Nothing here has been applied. Per memory
> `vps-no-dbpush`, no `db:push`/mutating step was run against `srv1464866`; the table drops would
> ship as a normal schema change merged to `main` and applied by the deploy path (see §4).
> Investigated against the Windows `dashboard/` tree this session — `git fetch` + confirm `origin/main`
> before acting, since this box's `main` drifts behind.

## TL;DR

The handoff framed this as "orphaned `/watch/sync` route + unused `watch_raw_events` / `watch_connections`
/ `watch_devices`." Investigation **partly disproves that**:

- **`/api/widget/v1/watch/sync` route — genuinely orphaned. SAFE to delete.**
- **`watch_connections` + `watch_raw_events` — write-dead** (their only writer is that route). Droppable,
  but each is still **read by the live Settings UI**, so the drop is not pure-additive — it needs three
  small code edits (below).
- **`watch_devices` — NOT unused. KEEP.** It is actively upserted by the **live** `/watch/health` route
  on every health sync and read by the Settings device list, and it is the FK parent of every
  `watch_*` health table. Dropping it is out of scope and would be destructive.

## 1. Evidence

### `/watch/sync` is orphaned
- Route: `dashboard/src/app/api/widget/v1/watch/sync/route.ts` — accepts the old `WatchSyncDto`
  (connection telemetry + raw protocol events), upserts `watch_devices`, inserts one `watch_connections`
  row, and inserts `watch_raw_events` (24 h TTL). No health metrics.
- Its only caller was the Android `DashboardApiClient.syncWatch` / `DashboardService` `watch/sync`
  declaration, which were **deleted** on the Android side — recorded in
  `dashboard-android/docs/roadmap.md` (“the now-dead Phase-2 connection-telemetry … `watch/sync`
  declaration … removed; `testDebugUnitTest` green after removal. (The dashboard server's
  `/api/widget/v1/watch/sync` route remains.)”).
- The current Android client declares only `@POST("api/widget/v1/watch/health")`
  (`DashboardService.kt:50`). Grep of `dashboard-android/` for `watch/sync` finds **only docs**, no live
  client code. → no caller exists; the route is dead.

### `watch_connections` / `watch_raw_events` are write-dead but still read
- **Only writer** of both tables is the orphaned sync route (`route.ts:286` connections, `:300` raw
  events). The live `/watch/health` route writes neither.
- **Readers (live Settings UI):** `dashboard/src/lib/watch/data.ts`
  - `listWatchDevices()` joins `watch_connections` for each device's latest `lastSeenAt` / `batteryPercent`
    / `connectionState`.
  - `listRawEvents()` reads `watch_raw_events`.
  - `purgeExpiredRawEvents()` deletes expired `watch_raw_events` (called only from the sync route).
  - `getLatestConnection()` reads `watch_connections` (no remaining caller found).
- Consumed by `dashboard/src/app/(app)/settings/page.tsx` (`listWatchDevices`, `listRawEvents`) and
  rendered by `settings/watch-devices-section.tsx` (connection badge, last-seen, battery, "Developer raw
  events" expander).
- **Net current behaviour:** because the writer is dead, every device card already shows a permanently
  degraded snapshot — badge `Unknown`, `Never seen`, no battery, `Developer raw events (0)` — while the
  device row itself still appears (it comes from `watch_devices`, written by `/watch/health`). The
  cleanup makes that honest rather than changing real behaviour.

### `watch_devices` is live — KEEP
- Upserted by the **live** `/watch/health` route (`health/route.ts:442`) on every sync.
- FK parent (`device_id`) of **all** `watch_*` health tables — `watch_activity_days`,
  `watch_heart_rate_days`, `watch_heart_rate_readings`, sleep/spo2/hrv/resp/temp/etc. — all
  `references(() => watchDevices.id, { onDelete: "set null" })` (schema.ts:1197–1543).
- Read by the Settings device list. **Do not drop.**

### FK graph (what blocks what)
```
users ─┬─< watch_devices ─┬─< watch_connections   (onDelete: cascade)   [DROP]
       │                  ├─< watch_raw_events     (onDelete: cascade)   [DROP]
       │                  │      └─ connectionId ─> watch_connections (cascade)
       │                  └─< watch_activity_days / watch_heart_rate_days /
       │                       watch_heart_rate_readings / …sleep/spo2/hrv/…
       │                                            (onDelete: set null) [KEEP — live]
       └─ (health tables also userId ─> users, cascade)
```
`watch_connections` and `watch_raw_events` are leaves (nothing references them except raw→connections),
so dropping them breaks no inbound FK. `watch_devices` has live children → not a drop candidate.

## 2. Proposed change set (for Fabian to apply, in order)

**Code (one PR, `dashboard` repo, PR-per-feature):**
1. Delete `src/app/api/widget/v1/watch/sync/route.ts` (whole `sync/` folder).
2. Trim `src/lib/watch/data.ts`: drop `getLatestConnection`, `listRawEvents`, `purgeExpiredRawEvents`,
   and the `watch_connections` lookup inside `listWatchDevices` (return device rows without
   `lastSeenAt` / `batteryPercent` / `connectionState`, or drop those fields from `WatchDeviceView`).
   Remove now-unused imports (`watchConnections`, `watchRawEvents`, `WatchConnection`, `WatchRawEvent`,
   `desc`/`sql` if unused).
3. Trim the UI: `settings/page.tsx` (drop `listRawEvents`/`rawEventsByDevice` and the removed
   `WatchDeviceView` fields) and `settings/watch-devices-section.tsx` (drop the connection badge,
   last-seen, battery, and the "Developer raw events" expander; keep the device name/model/identifier
   card). Remove `WatchRawEventView` and now-unused `lucide-react` icons.
4. Remove the three table defs + their inferred types from `src/lib/db/schema.ts`
   (`watchConnections`, `watchRawEvents`, and the `WatchConnection`/`WatchRawEvent`/`NewWatch*` types).
   **Leave `watchDevices` untouched.**
5. Verify: `npx tsc --noEmit` (expect only the pre-existing unrelated errors), `npx eslint` on the
   touched files, and check for any other importer of the removed `data.ts` exports before deleting.

**Schema/DB:** the drop reaches the live DB the same way the watch schema arrived — drizzle-kit `push`
on deploy diffs `schema.ts` against the DB and drops the two removed tables (see §4). No hand-written
SQL needed.

## 3. Alternative: keep the tables, delete only the route

If you'd rather not touch the Settings UI, a smaller change is: delete the orphaned route only, and
leave `watch_connections` / `watch_raw_events` in place as empty tables the UI keeps reading (it already
renders the degraded snapshot gracefully). This removes the dead write-path/attack-surface with zero UI
risk, at the cost of leaving two unused tables in the schema. The route deletion is the high-value, no-risk
part; the table drop is optional tidiness.

## 4. How the DB drop actually applies (do NOT run on the VPS)

- The dashboard has **no SQL migration files** — `package.json` exposes `db:push`
  (`drizzle-kit push`), and `drizzle.config.ts` points `out` at `./drizzle` but the schema is reconciled
  by diffing, not by versioned migrations.
- Memory `vps-no-dbpush`: do **not** run `db:push` (or any mutating step) on `srv1464866` from here —
  active coding processes; the deploy path applies schema from `main`.
- On SQLite, `drizzle-kit push` will want to **DROP** `watch_connections` and `watch_raw_events`
  (drizzle prompts/auto-confirms destructive drops). That data is dev-only / TTL'd and write-dead, so
  loss is acceptable — but **back up first** (`npm run db:backup`) and let Fabian run/confirm the push as
  part of deploy. Order: merge the code PR → backup → push → verify the Settings page still renders the
  device list.

## 5. Out of scope / explicitly NOT proposed
- Dropping or altering `watch_devices` or any `watch_*` health table.
- Touching the live `/watch/health` route.
- Running `db:push`, a manual `DROP TABLE`, or any write against the live VPS from this session.
