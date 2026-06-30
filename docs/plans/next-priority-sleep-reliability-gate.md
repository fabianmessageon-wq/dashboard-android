# Next priority — sleep nap/main-night reliability gate

> Note-to-self captured 2026-06-30, after pushing `android/jared-notification-bridge`.
> Pick this up next. Several steps are hands-on (watch wear) or need a decision —
> read the caveats before running anything.

## Verification status (2026-06-30, SM-G991B)

**Android half: VERIFIED.** The V3 sleep mapper carries the onset discriminator —
`HealthSleepV3.fall_asleep_*` → `WatchSleepSession.startDateTime`, with `date` keyed
to the wake-up ("get up") day — so a nap and the main night sharing a wake date map
to the **same `date`, distinct `startDateTime`** (v2 falls back to midnight-of-wake-
date). Unit-covered; `testDebugUnitTest` green (114). Source: `IdoSdkWatchEngine.kt`
`HealthSleepV3.toDomain()` (~L1995) + `WatchHealthModels.kt`.

**End-to-end (dual-row) half: BLOCKED — deferred by Fabian 2026-06-30.** Two blockers,
neither solvable from an adb-only Windows session:
1. **No sleep to exercise it.** Sync is incremental; last night's sleep is already
   synced and there's no nap. Across **5 worker syncs this session the watch emitted
   0 SLEEP sessions** (it re-emits only today's rolling metrics: body-energy, HR-sec,
   stress, activity). No app hook resets the IDO per-type sync offset to force a
   re-pull, so existing sleep can't be re-observed without code or fresh wear.
2. **No prod-DB read.** The "two rows, distinct `started_at`, idempotent" assertion
   lives in `watch_sleep_sessions` on `srv1464866`; a read-only SSH was policy-denied
   this session. Needs explicit authorization (or Fabian runs the `sqlite3` SELECT).

**To close:** wear the watch for a nap **and** a main night on one wake date → force-
stop VeryFit → `DEBUG_SYNC_NOW -f 0x20` → confirm the Android emits two same-date
`SLEEP WatchSleepSession(...)` with distinct `startDateTime`, then read-only-verify two
DB rows + re-sync idempotency (runbook §A/§B below).

## Goal

Close the sleep nap/main-night data-loss fix end to end: a nap and a main night
that share one wake date must persist as **two** `watch_sleep_sessions` rows
(distinct `started_at`), and re-syncing must stay idempotent. See the fix in
[[sleep-nap-collision-fix]] (unique key now `(user, date, started_at)`).

## Sequence

1. **Land/merge `android/jared-notification-bridge` first.** (Don't merge without
   Fabian's go-ahead — open the PR, he merges.)
2. **Confirm the dashboard sleep schema change is deployed.**
   ⚠️ Do **not** `db:push` on `srv1464866` — active coding processes, and
   `origin/main` already carries the watch schema so deploys handle it
   ([[vps-no-dbpush]]). "Confirm deployed" = verify the live schema/migration ran
   (+ a backup exists), **not** run `db:push` here.
3. After a **real nap and main night share one wake date**, force one sync
   **before VeryFit reads the watch** (VeryFit auto-restarts and reclaims the
   single BLE owner — force-stop `com.watch.life` immediately before).
   - Trigger the sync headless via the debug broadcast:
     `am broadcast DEBUG_SYNC_NOW -f 0x20` (recipe in [[item4-bg-worker-status]]).
4. **Verify two `watch_sleep_sessions` rows** with distinct `started_at`.
5. **Re-sync and confirm idempotency** — still two rows (no duplicate, no
   collision/overwrite).
6. **Update the stale test plan and roadmap** docs to reflect the verified state.

## After the sleep gate

- If sleep data isn't available yet, do the **PCAPdroid network-egress capture**
  next (unrooted-phone VpnService tooling — the still-open item from
  [[item4-bg-worker-status]]).
- Then: **respiratory / v2-HR matrix closeout**, then the remaining **human music
  checks**.

## Deferred

- DFU / watch-face work — hold until the reliability gates above are closed.

## Hands-on vs. solo (from the 2026-06-30 triage)

- **Solo, safe now:** verify (not push) the deployed sleep schema; refresh the
  test-plan/roadmap docs (step 6).
- **Needs Fabian:** the watch wear + nap/night + timed sync (steps 3–5).
- (PRs/merges are out of scope here — branch is already pushed.)

## Hardware-session runbook (do all of these in one watch sitting)

Everything below is code-ready and turnkey — no code change needed to verify,
just wear/sync and read the diagnostics. Force-stop VeryFit (`am force-stop
com.watch.life`) immediately before each sync (single BLE owner).

### A. Sleep dual-row (steps 3–5 above)
- After a nap + main night sharing one wake date, sync once, then check the prod
  DB for **two** `watch_sleep_sessions` rows with distinct `started_at`; re-sync
  and confirm still two (idempotent).

### B. Sleep schema is live on the VPS (one command — confirms step 2's deploy)
```bash
sqlite3 /home/apolytus/workspace/dashboard/data/dashboard.db \
  "PRAGMA index_list('watch_sleep_sessions');"
```
Expect `watch_sleep_user_started_uniq`. Schema is on `origin/main` and
`deploy/update.sh` auto-applies it via `db:push --force` when `schema.ts` changed,
so a normal deploy already ran it — this just confirms. (Caveat: a `--force` push
adding the new unique constraint fails if pre-existing rows already collide on
`(user,date,started_at)`; if the index is missing, check row collisions first.)

### ⚠ Shared blocker found 2026-06-30: incremental sync hides already-synced metrics
A worker sync emits the privacy-safe tally `sync diagnostics: …(p=,i=,m=)` at
`onSuccess` (tag `IdoSdkWatchEngine`, not `WatchBLE`). Across **5 syncs this
session** it only ever listed `stress`, `activity_day_v3`, `body_energy`,
`heart_rate_second` — i.e. **only today's rolling intraday metrics**. Everything
keyed by completed-day / already-synced — **sleep_v3, respiratory, heart_rate_day_v2,
hrv, spo2** — was **absent**, because the IDO SDK advances a per-type sync offset
(`SetSyncHealthOffset(type, offset)`) and won't re-deliver a window it already sent.
Consequence: **§A (sleep dual-row) and §C below cannot be re-observed on demand.**

**Offset-reset hook investigated 2026-06-30 → NOT viable; do not retry it blindly.**
- A debug hook to reset the per-type cursor would need the native
  `Protocol.SetSyncHealthOffset(int type, int offset)`, reachable only via obfuscated
  internals (`com.ido.ble…umbra.basilisk(int,int)`) with **undocumented per-type
  codes** — fragile black-box code the project rules forbid, and wrong codes risk
  corrupting the SDK sync cursor.
- The clean public `com.ido.ble.LocalDataManager` can re-emit *stored* records, but
  **has no getter for the V3 types that matter** (`HealthSleepV3`,
  `HealthRespiratoryRate`, `HealthHRVdata`, `HealthBodyPower`; only v2 types + SpO2 +
  v2-HR-day are exposed). And re-emitting stored data wouldn't answer §C anyway — the
  matrix question is about **watch delivery**, not replaying local storage.

**Therefore the only sound path is genuinely fresh / unsynced data:** wear the watch so
a new window (a real nap **and** a main night for §A) is pending, then sync. The prod
DB read (read-only `sqlite3`, with authorization) confirms the server-side result.
(A research spike to fully document `SetSyncHealthOffset`'s type codes could revisit a
guarded hook later, but that's a separate, non-trivial task — not a quick win.)

### C. Respiratory + v2 heart-rate-day matrix closeout (both code-ready)
Both handlers map + forward to the listener **and** add to the sync-diagnostics
tally, so a single sync confirms emission via `sync diagnostics: …` — **but see the
shared blocker above: neither fired this session (already-synced).**
- **Respiratory:** look for `respiratory(p=…,i=…,m=…)` with `m>0` → upload →
  `<-- 201` → rows. Then flip the matrix Respiratory row off `SDK_MODEL_ONLY`.
- **v2 HR-day:** look for `heart_rate_day_v2(…)`. If it never appears **on a sync
  that actually carries an unsynced HR-day window** (not merely an offset-skipped
  one), the row's "likely never fires (V3 watch)" is confirmed — annotate as such and
  stop chasing it (intraday HR already covers HR, verified 2026-06-29).

### Then (still hardware, lower priority)
PCAPdroid network-egress capture → remaining human music checks. Defer
DFU/watch-face.
