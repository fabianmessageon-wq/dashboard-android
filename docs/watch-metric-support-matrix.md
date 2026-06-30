# Active 4 Pro — watch metric support matrix (Phase 2)

Device: **Kogan Active 4 Pro** (IDO/VeryFit V3 family), MAC `F4:**:**:**:C6:45`
Engine: vendored IDO SDK private engine (`IdoSdkWatchEngine`, ADR 0001)
Evidence captured: **2026-06-26** on Samsung SM-G991B (Android 14), debug build of
`phone-integration-refinement`.

This matrix is produced by the Phase-2 instrumentation, not by guesswork:

- **Function-table** column = the watch's own `SupportFunctionInfo` capability flag, logged at
  connect (`function table (health flags): …`). The flag name per metric lives in
  [`WatchMetric`](../app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/WatchMetricSupport.kt).
- **Emitted on real sync** = what the watch actually delivered, logged at sync end
  (`sync diagnostics: …` + `metric confidence: …`) from
  [`WatchSyncDiagnostics`](../app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/WatchSyncDiagnostics.kt).
- **Uploaded / Shown in UI** = static pipeline facts (upload DTO / Watch-screen `CountRow`).

## Confidence ladder

Each metric is labelled with the highest level **proven** for it. The lower rungs are
device-independent (our SDK/pipeline); the upper rungs require the watch to advertise and deliver
the metric. Pipeline rungs only count once the metric is actually emitted, so a metric we *could*
upload/show but the watch never sends is never overstated.

1. `SDK_MODEL_ONLY` — an SDK type + mapper exists.
2. `FUNCTION_TABLE_SUPPORTED` — the connected watch advertises it.
3. `EMITTED_ON_REAL_SYNC` — a real sync delivered ≥1 record.
4. `UPLOADED_TO_DASHBOARD` — …and we map + upload it.
5. `SHOWN_IN_UI` — …and the Watch screen renders it.

## Matrix

| Metric | SDK model | Function table (A4P) | Emitted on real sync | Uploaded | Shown in UI | Confidence | Next action |
|---|---|---|---|---|---|---|---|
| Activity day | ✅ (HealthSportV3) | flag reads **false**¹ | ✅ 1/day | ✅ | ✅ | **SHOWN_IN_UI** | Done. Verify `ex_main4_v3_activity_data` is the right flag (emits despite false). |
| Body energy | ✅ (HealthBodyPower) | ✅ `v3_body_power` | ✅ 251, then 33/295² | ✅ | ✅ | **SHOWN_IN_UI** | Done. Investigate zero-slot padding (295 items → 33 non-zero). |
| Stress | ✅ (HealthPressure) | ✅ `ex_main3_v3_pressure` | ✅ 24, then 4/9 | ✅ | ✅ | **SHOWN_IN_UI** | Done. |
| HRV | ✅ (HealthHRVdata) | ✅ `V3_support_hrv` | ✅ 1/6 | ✅ | ✅ | **SHOWN_IN_UI** | Done. |
| Workout / sport | ✅ (HealthActivityV3) | ✅ `ex_table_main9_v3_sports` | ✅ 1 (2026-06-28)⁵ | ✅ | ✅ | **SHOWN_IN_UI** | Done. Verified end-to-end 2026-06-28 (`workout_v3(p=1,i=0,m=1)` → 201). |
| Sleep | ✅ (HealthSleepV3) | ✅ `V3_support_scientific_sleep` | ✅ 1 night (2026-06-29)⁶ | ✅ | ✅ | **SHOWN_IN_UI** | Done. Verified end-to-end 2026-06-29 (prod row: total 504, REM 142/17, avg-HR 49, score 90). ⚠ nap/main-night collision on `(user_id, date)` — see note 6. |
| SpO₂ | ✅ (HealthSpO2) | ✅ `ex_main3_v3_spo2_data` | ✅ 1 (2026-06-28)⁵ | ✅ | ✅ | **SHOWN_IN_UI** | Done. Verified end-to-end 2026-06-28 (`spo2(p=1,i=4,m=1)`, percent=97 → 201). |
| Heart-rate day | ✅ (HealthHeartRate, v2) | ✅ `heartRate` | ⬜ none (v2 path) | ✅ | ✅ | FUNCTION_TABLE_SUPPORTED | Likely never fires (V3 watch). HR may live in `heart_rate_second` instead. **Code-ready:** `onGetHeartRateData` maps → `onHeartRateDay` and records a `heart_rate_day_v2` diagnostic, so one sync confirms fires-or-not on-device (no code change needed to verify). |
| Respiratory | ✅ (HealthRespiratoryRate) | — no flag | ⬜ none yet | ✅ | ✅ | SDK_MODEL_ONLY | No capability flag to lean on. **Code-ready:** `onGetHealthRespiratoryRate` maps each item (`start_time`=minute-of-day, `respid` bpm) → `onRespiratoryReading` and records a `respiratory(p,i,m)` diagnostic, so on-watch breathing data confirms emission at a glance; needs that data to flip the label. |
| Intraday HR (`heart_rate_second`) | ✅ (HealthHeartRateSecond) | — no flag | ✅ 330/day⁴ | ✅ | ✅ | **SHOWN_IN_UI** | Done 2026-06-29. `item.offset` is a per-item **delta in seconds** — accumulate (`recordedAt = midnight + Σoffset`); see §"Intraday HR investigation". |
| Swimming | ✅ (HealthSwimming) | ✅ `pool_swim` | ⬜ none yet | ❌ | ❌ | FUNCTION_TABLE_SUPPORTED | Out of scope unless wanted; needs domain model + schema. |
| Ambient noise | ✅ (HealthNoise) | ✅ `V3_health_sync_noise` | ⬜ none yet | ❌ | ❌ | FUNCTION_TABLE_SUPPORTED | Low priority; needs domain model + schema. |
| Temperature | ✅ (HealthTemperature) | ❌ `V3_health_sync_temperature` | ⬜ none | ✅ | ✅ | SDK_MODEL_ONLY | No sensor on A4P — leave as-is. |
| Blood pressure | ✅ (HealthBloodPressureV3) | ❌ `BloodPressure` | ⬜ none | ✅ | ✅ | SDK_MODEL_ONLY | No sensor on A4P — leave as-is. |
| GPS track | ✅ (HealthGpsV3) | ❌ `ex_gps` | ⬜ none | ❌ | ❌ | SDK_MODEL_ONLY | No on-watch GPS — leave as-is. |
| Emotion / mood | ✅ (HealthV3EmotionHealth) | ❌ `support_emotion_health` | ⬜ none | ❌ | ❌ | SDK_MODEL_ONLY | Categorical mood code, not a 0–100 score; skip. |
| Body composition | ✅ (HealthBodyComposition) | — no flag | ⬜ none | ❌ | ❌ | SDK_MODEL_ONLY | Needs a bio-impedance scale; won't fire on a wrist watch. |
| ECG | ✅ (HealthV3Ecg) | — no flag | ⬜ none | ❌ | ❌ | SDK_MODEL_ONLY | Unknown if A4P records ECG; no clean mapping. |

Notes:
1. `activity_day_v3` (`ex_main4_v3_activity_data`) reads **false** in the function table, yet the V3
   daily rollup still emits and maps. Emission is the ground truth; the flag name is likely the
   wrong capability bit for the daily rollup. Action: revisit the flag, but do not gate on it.
2. The first full sync delivered 251 body-energy + 24 stress + 1 activity day (276 total). A later
   incremental sync delivered 39 (33 body-energy of 295 raw items, 4 stress of 9, 1 activity, 1
   HRV) — confirming sync-offset bookkeeping (no re-pull) and heavy zero-slot padding in the raw
   item buffers (we already skip `value <= 0`).
3. "None yet" = the watch **advertises** support but no record fell in the synced window (no
   workout/sleep/SpO₂ in the incremental data). Capability is proven; emission needs the relevant
   activity on the watch, then a sync.
4. `heart_rate_second` is **delivered, mapped, and uploaded** (resolved 2026-06-29). The per-item
   `offset` is a delta in seconds since the previous item; accumulating it gives the wall clock. See
   the §"Intraday HR investigation" resolution note. ~330 readings/day verified end-to-end.
5. **Verified 2026-06-28** (SM-G991B, debug build). After recording a workout + an SpO₂ reading on
   the watch, a sync delivered `workout_v3(p=1,i=0,m=1)` and `spo2(p=1,i=4,m=1)` (SpO₂ percent=97);
   both reached `metric confidence: …=SHOWN_IN_UI`, and the batch uploaded real (`--> POST
   …/watch/health` → `<-- 201`, 569 stored). VeryFit was force-stopped and logcat captured first.
6. **Sleep verified 2026-06-29** against the **live production DB**
   (`/home/apolytus/workspace/dashboard/data/dashboard.db`, WAL-mode, served on `:8443`). The
   `watch_sleep_sessions` row for `2026-06-29` matches the ido-log decode exactly: total 504, deep 97,
   light 258, **rem 142 / rem_count 17**, avg_heart_rate 49, score 90, avg_spo2 NULL (this firmware's
   function table marks `..._add_sleep_avg_spo2`/`_avg_respir_rate` **false** → those sleep averages are
   never emitted; only avg-HR lands). Capture caveat: last night's sleep was consumed by an **earlier
   same-day sync (our own app's auto-sync, not VeryFit)** — sleep does **not** re-deliver once read
   (unlike current-day intraday HR), so a controlled before-VeryFit re-sync returns all-zeros; the durable
   `files/ido-logs/*.log` is the safety net. **⚠ Open bug — nap/main-night upsert collision:** the
   server upserts sleep on `unique(user_id, date)` (`watch/health/route.ts`), but this watch emits naps
   **and** a main night both stamped with the same wake-date, so whichever is written last wins. Proof:
   the `2026-06-28` prod row is total **32** (a nap), having clobbered that night's real **475**-min main
   sleep (present in the 06-28 ido-log). `2026-06-29` is correct only because no nap shared its date.
   Fix is schema/DTO-side (discriminate naps, e.g. key on session start or a `is_nap` flag) — propose
   before touching prod (`vps-no-dbpush`).

## Intraday HR investigation (2026-06-26)

The next slice was to surface the HR the watch sends. Instrumented the `heart_rate_second` record
sub-fields and resynced; findings:

- The v2 daily-HR path (`onGetHeartRateData`) never fires — HR comes only via `heart_rate_second`.
- **Every timestamped/aggregate field is empty on this watch:** `silentHR = 0`, `five_min_data`
  empty, `hr_data` (the high/low list that *does* carry `hour:minute`) empty, `hr_data_count = 0`.
- The only data is `items[]`: ~282→286 bare `heartRateVal` ints over the day, `startTime = 0`, **no
  per-sample timestamp**. Values are a mix of `0` and real BPM (e.g. last three = `[65, 0, 58]`).
- Non-zero values run to the **tail** of the array while the day was only ~19:40 — inconsistent
  with a clean 5-minute time-of-day grid (which would fill only up to "now"). Count growth was
  slow/noisy (282→286 over 7 min), and delivery is intermittent (periodic chunk, not every sync).

**Conclusion (2026-06-26 — SUPERSEDED):** the `items[]` samples cannot be reliably placed on the wall
clock from the delivered record alone… *This was wrong: the 2026-06-26 probe never logged the
per-item `offset` field, so it looked like the items were bare BPM ints.*

### RESOLUTION (2026-06-29) — delta-seconds, hardware-verified

The enhanced probe logged `item.offset`, and the encoding is now settled and verified end-to-end:

- Each `HealthHeartRateSecondItem.offset` is a **delta in seconds since the previous item** (byte-sized,
  `0..255`). Wall clock = the **running sum**: `cum += item.offset; recordedAt = midnight(y,m,d) + cum`.
  `startTime` is 0/unused.
- Gaps wider than a byte are split into `offset=255, heartRateVal=0` continuation sentinels (a long
  unworn stretch = a run of consecutive 255s). The hr=0 sentinels skip the `1..250` bpm guard but their
  offset **must still accumulate** or every later sample drifts.
- Unit pinned to **seconds** by three independent anchors: a full day (2026-06-28) sums to 86109 s →
  last sample 23:55:09 (~24 h span); the partial current day (2026-06-29) to 38721 s → last 10:45:21,
  ~4 min before the 10:49 sync; and the native C layer logs `start_time`/`end_time` in seconds-of-day
  (38721→39040) matching exactly. Reconstructed against the raw `files/ido-logs/*.log` JSON.
- A first buggy sync (raw offset, not accumulated) crammed all readings into 00:00–00:04 and uploaded
  garbage rows for 06-28/06-29 → review-only fix in `docs/plans/hr2nd-dashboard-cleanup.sql`.

**Caution for future RE:** the misleading first oracle read was VeryFit's `BaseMeasurementPresenter.getOffset`,
which is the *manual one-click-measure writer* (absolute), NOT the device-sync decoder (delta). When the
decompile and the live wire disagree, the **live wire wins** — always confirm a VeryFit-derived formula
against a real probe.

## Phase-2 conclusion & next slice

- **Instrumentation + verification: complete.** Every SDK callback is now tallied; a real sync logs
  a counts-only diagnostics summary, a delivered-but-dropped list, and a per-metric confidence line.
- **Proven end-to-end (SHOWN_IN_UI):** activity day, body energy, stress, HRV, **workout (2026-06-28),
  SpO₂ (2026-06-28)**, **intraday HR (2026-06-29)**, **sleep (2026-06-29, prod-DB verified — note 6)**.
- **Capable but unverified (need on-watch activity):** respiratory, heart-rate-day (v2 path, likely
  never fires on a V3 watch). All cleanly-exposed metrics the watch advertises are now confirmed.
- **Intraday HR:** RESOLVED 2026-06-29 — delta-seconds accumulation, verified end-to-end (above).
- **Open bug (sleep):** nap/main-night upsert collision on `unique(user_id, date)` clobbers the main
  sleep when a nap shares the wake-date (06-28: 475-min night → 32-min nap). Fix is schema/DTO-side;
  propose before touching prod. See note 6.

Recommended next step: confirm the **capable-but-unverified** metrics (workout/sleep/SpO₂) by
syncing after the relevant on-watch activity — that needs **no code**, just data, and closes the
matrix for everything the watch actually exposes cleanly. Only pursue intraday HR if a VeryFit
btsnoop capture is available to pin the sample cadence. Do **not** expand into GPS/swim/noise/TCX/
cloud or generic smartwatch support.
