# Sleep nap/main-night upsert collision — fix + migration note

> Implemented 2026-06-29 (handoff item 2). Code is in the working tree (uncommitted — see "Files"
> below); **`db:push` is yours to run** on the live VPS per [[vps-no-dbpush]]. Read before pushing.

## The bug (real data loss)

`watch_sleep_sessions` upserted on `unique(user_id, date)`. The Active 4 Pro
(`support_display_nap_sleep=true`) emits **naps + a main night that share one wake date**, so the
last write of a sync won. Prod proof: `2026-06-28` row = total **32 min** (a nap) — it clobbered
that night's real **475 min** main sleep (still in `files/ido-logs/20260628.log`). `2026-06-29`
only survived because no nap shared its date.

## The fix — onset timestamp as discriminator (mirrors the workout pattern)

`HealthSleepV3` carries a distinct `fall_asleep_*` time per session, so each nap and the main night
has its own onset. We now key sleep on `(user_id, date, started_at)` — exactly how workouts already
key on `(user_id, started_at)`. A nap and the night get separate rows; re-uploading the same session
stays idempotent.

**Zero read-side risk:** `watch_sleep_sessions` is **write-only** — the dashboard reads sleep from
`sleepLogs` (manual logs), never this table. Confirmed by grep: the table appears only in `schema.ts`
and the `/watch/health` route. So multiple rows per date break no UI/intelligence reader.

### What changed
- **schema.ts** `watchSleepSessions`: added nullable `startedAt` (`started_at`, epoch seconds);
  replaced `unique(user_id, date)` → `unique(user_id, date, started_at)`
  (`watch_sleep_user_started_uniq`). `(user_id, date)` index kept.
- **`/watch/health` route.ts**: `SleepSession.startedAt` added, parsed via `intOrNull`, and the
  upsert `target` is now `[userId, date, startedAt]`.
- **Android** (`watch/intraday-hr-second` working tree):
  - `WatchSleepSession` engine model: `startDateTime: String?` (local wall-clock onset).
  - V3 mapper (`HealthSleepV3.toDomain`): `startDateTime` from `fall_asleep_*` (null on 0-year
    sentinel).
  - v2 mapper (`HealthSleep.toDomain`): no fall-asleep field + no naps on those devices → uses
    midnight-of-wake-date as a stable, idempotent dedup key.
  - DTO `WatchSleepSessionDto.startedAt: Long?` + `toDto` converts via the existing
    `localWallClockEpochSeconds` (same helper workouts use).

### Verified
- Dashboard `npx tsc --noEmit`: no new errors in `schema.ts` / `watch/health` (only the pre-existing
  `perfect-freehand` + `hermes-routes.test.ts` failures remain).
- Android `./gradlew testDebugUnitTest`: **BUILD SUCCESSFUL**, unit tests green (incl.
  `CompositeWatchHealthListenerTest`). Not yet hardware-verified end-to-end (needs a real sync that
  carries both a nap and a night on one date).

## Migration — how `db:push` applies this (safe, no NOT-NULL backfill trap)

`started_at` is **nullable on purpose**:
1. drizzle-kit push adds it as a plain `ADD COLUMN` (nullable → no table rebuild, no default needed).
2. The unique index swaps from `(user_id,date)` to `(user_id,date,started_at)`.
3. Existing prod rows get `started_at = NULL`. SQLite treats NULLs as distinct in UNIQUE, so they
   don't collide with each other or with new rows.

**One-time caveat:** because the upsert target needs a real value to match on, new uploads always
send a non-null `started_at`. If a *pre-existing* date ever re-syncs, its new (non-null-onset) row
won't match the old NULL-onset row → a one-time duplicate for that date. Watches only re-sync recent
days, so in practice this affects nothing; clean up by hand if it ever appears.

Apply order (yours): merge the dashboard PR → `npm run db:backup` → `npm run db:push` (confirm it
adds `started_at` + the new unique, drops the old unique) → spot-check the Settings page still loads.

## Optional: recover 06-28's clobbered 475-min night

Not done (needs the decoded values from `files/ido-logs/20260628.log`, and the watch won't re-deliver
a past day). If wanted, I can generate a one-row backfill `INSERT` (same style as
`hr2nd-dashboard-cleanup.sql`) with the correct totals + a real `started_at`. Say the word and I'll
pull the log and build it.

## Files (uncommitted, grouped for a clean PR)

`dashboard` (currently on `watch/intraday-hr-second` — split onto its own branch, e.g.
`watch/sleep-nap-discriminator`):
- `src/lib/db/schema.ts`
- `src/app/api/widget/v1/watch/health/route.ts`

`dashboard-android` (on `watch/gatt133-retry-and-metric-docs`):
- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/WatchHealthModels.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/IdoSdkWatchEngine.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/data/api/dto/WatchHealthDto.kt`
