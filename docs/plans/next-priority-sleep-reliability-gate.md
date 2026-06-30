# Next priority — sleep nap/main-night reliability gate

> Note-to-self captured 2026-06-30, after pushing `android/jared-notification-bridge`.
> Pick this up next. Several steps are hands-on (watch wear) or need a decision —
> read the caveats before running anything.

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

### C. Respiratory + v2 heart-rate-day matrix closeout (both code-ready)
Both handlers map + forward to the listener **and** emit a diagnostic line, so a
single sync confirms emission at a glance (`adb logcat -s WatchBLE:D`):
- **Respiratory:** look for `respiratory(p=…,i=…,m=…)` with `m>0` → upload →
  `<-- 201` → rows. Then flip the matrix Respiratory row off `SDK_MODEL_ONLY`.
- **v2 HR-day:** look for `heart_rate_day_v2(…)`. If it never appears, the row's
  "likely never fires (V3 watch)" is **confirmed** — annotate as such and stop
  chasing it (intraday HR already covers HR, verified 2026-06-29).

### Then (still hardware, lower priority)
PCAPdroid network-egress capture → remaining human music checks. Defer
DFU/watch-face.
