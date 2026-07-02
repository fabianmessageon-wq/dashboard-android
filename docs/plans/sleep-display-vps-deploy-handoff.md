# Handoff — deploy + verify watch-sleep display (VPS agent)

This is a ready-to-run prompt for an agent **on the VPS** (`srv1464866`, reachable at the
dashboard workspace `/home/apolytus/workspace/dashboard`). The local dev box can't reach the VPS
(Tailscale-only, passphrase-protected key, no ssh-agent), so the deploy + prod-DB checks are handed
off here rather than run locally.

## What changed locally (context)

The watch **sleep ingest** was already live; this batch adds the **downstream display + consumption**
and an Android reliability fix:

- **dashboard (read + UI):** new `src/lib/watch/sleep.ts` read layer; `src/app/(app)/health/page.tsx`
  now renders a rich **Watch sleep** card (new `watch-sleep-card.tsx`) that *replaces* the manual
  sleep-log form when the user has synced watch sleep (falls back to manual otherwise); the sleep
  trend + "Last sleep" stat read watch data when present.
- **dashboard (Daily Intelligence):** `src/lib/intelligence/context.ts` now prefers the watch
  main-night sleep over the manual `sleep_logs` (mapping total→hours, score→1–10 quality), so the
  planner's readiness reflects measured sleep. Stale "sleep ingestion isn't built yet" comments in
  `daily-intelligence/morning.ts` + `morning-trigger/route.ts` were refreshed.
- **dashboard-android (no VPS action needed):** failed watch-health POSTs now spool to a durable
  on-device queue (`WatchHealthUploadQueue`) and retry on the next sync instead of being dropped.

All local checks pass: dashboard `tsc --noEmit` clean (only pre-existing unrelated errors),
`tests/unit/watch-sleep.test.ts` green, Android `:app:testDebugUnitTest` green.

## The read side depends on the `started_at` migration

The rich V3 columns (`rem_minutes`, `avg_heart_rate`, …) are already live on prod. **Still pending
is the nap-collision fix's `started_at` column + re-keyed unique index** `(user_id, date, started_at)`
— see the `sleep-nap-collision-fix` note. Without it, a nap and the main night sharing a wake date
still collide (last write wins) and the new UI would show a nap instead of the night.

## Tasks for the VPS agent

1. **Confirm the schema is on `main`.** In `/home/apolytus/workspace/dashboard`, verify
   `src/lib/db/schema.ts` `watchSleepSessions` has the `started_at` column and the
   `watch_sleep_user_started_uniq` unique index on `(userId, date, startedAt)`. If it's still only on
   a feature branch (`watch/sleep-and-sync-route-cleanup`), get that PR merged to `main` first — do
   **not** deploy uncommitted schema.

2. **Run the additive migration.** `npm run db:push`. This adds the nullable `started_at` column and
   swaps the unique index — additive, no NOT-NULL backfill trap. **Respect `vps-no-dbpush`:** do not
   push while active coding processes are running on the box; coordinate timing. Expect a one-time
   harmless duplicate only if a pre-existing wake date re-syncs (old NULL-onset row + new real-onset
   row); old dates don't re-sync.

3. **Deploy the dashboard** (build + restart per the box's normal deploy flow for the `:8443`
   service) so the new `/health` UI + DI context ship.

4. **Verify today's sleep landed and is keyed correctly.** Read-only query against the served prod DB
   (`/home/apolytus/workspace/dashboard/data/dashboard.db`, WAL). Confirm for the watch user:
   ```sql
   SELECT date, started_at, total_minutes, deep_minutes, light_minutes, rem_minutes,
          score, avg_heart_rate, avg_spo2
   FROM watch_sleep_sessions
   WHERE date >= date('now','-2 day')
   ORDER BY date DESC, total_minutes DESC;
   ```
   Expect: today's **main night** populated (`total/deep/light/rem_minutes`, `score`,
   `avg_heart_rate`; `avg_spo2`/`avg_respiratory_rate` NULL — this watch never emits them). If a nap
   exists it should be a **separate row** with the same `date` but a distinct `started_at` (proves the
   re-key works). Report the rows back so they can be cross-checked against the device's durable
   `files/ido-logs/YYYYMMDD.log` capture.

5. **Sanity-check the UI:** open `/health` as the watch user — the Watch-sleep card should show last
   night's stage breakdown + score + resting HR, and the trend should plot recent nights.

## Report back

- Whether `started_at` was already on `main` or needed a merge.
- `db:push` output (columns added / index swapped).
- The `watch_sleep_sessions` rows from step 4 (so they can be reconciled with the ido-log).
- Any deploy issues.
