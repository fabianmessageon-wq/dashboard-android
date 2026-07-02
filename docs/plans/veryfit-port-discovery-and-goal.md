# VeryFit port discovery pass and refined /goal

## Context

`../../veryfit-breakdown/` is the decompiled reference app (VeryFit `com.watch.life` v3.4.0)
for the Kogan Active 4 Pro / IDO watch SDK. This document is the result of a discovery pass
comparing it against `dashboard-android` (and, where relevant, the `dashboard` web app) across
eight areas, before committing to an implementation goal. No implementation happened in this
pass — this is findings + a refined goal + decisions.

**Standing architectural decision (2026-07-02):** `dashboard-android` is a **temporary
build**. Its long-term role is narrow: it is a **bridge** — it talks BLE to the watch, uploads
decoded health data to the dashboard, and receives/display notifications. It is **not** where
product surfaces like Jared AI should live. The current `dashboard-android` + `dashboard` pair
will eventually be converted into a proper backend + frontend for wider deployment; don't
over-invest in Android-side UI polish beyond what the bridge role needs.

## Findings per area

### 1. Calling bug (answer vs. decline)

**Static-analysis finding:** both answer and reject look wired end-to-end
(`IdoSdkWatchEngine.kt` → `WatchConnectionService.kt:113-146` → `TelecomManager`), with an
apparent asymmetry — reject uses `TelecomManager.endCall()` (works with just
`ANSWER_PHONE_CALLS`, API 28+), while answer uses `TelecomManager.acceptRingingCall()`, which
actually requires `MODIFY_PHONE_STATE` (system/signature-only) or default-dialer/carrier
privilege. `ANSWER_PHONE_CALLS` alone throws a `SecurityException`, silently swallowed by
`runCatching{}.onFailure{ Log.w(...) }` in `answerCall()`.

**This conflicts with an existing hardware-verified memory (`w7-notification-hardware`,
2026-06-28) that must take precedence:** the Active 4 Pro is dual-bonded as Bluetooth
**Handsfree (HFP) + HID**, and wrist answer/reject is executed by **Android's native telephony
stack over HFP** — the app's `deviceControlCallBack`/`WatchConnectionService` control path was
verified **never to fire** on a real wrist tap (no `onControlEvent` observed). That whole SDK
call-control code path — including the `answerCall()`/`rejectCall()` functions the static
analysis flagged — is dead code on this hardware, kept only as a fallback for watches that
deliver control over the IDO GATT channel instead of HFP. The memory records "call
display+reject" as hardware-verified PASS, but does not confirm answer was tested working.

**Revised verdict:** the `acceptRingingCall()` permission gap is real but is very likely
**not** the actual cause of the reported symptom, since that code never executes on this
watch. The more likely explanation is an **HFP-level limitation** — some Bluetooth accessories
support remote hang-up/reject over HFP but not remote-answer, which would be a watch
firmware/profile constraint, not an app bug at all. veryfit-breakdown has the identical
`acceptRingingCall()` gap in its (equally dead, on this hardware) SDK call-control path, so
there's nothing to port either way.

**Before any fix is attempted:** this needs a live runtime check —
`adb shell dumpsys bluetooth_manager` during an incoming call, plus a wrist-tap answer
attempt with logcat open, to confirm whether HFP itself rejects the answer command or whether
it succeeds and something else swallows it. Don't implement an `InCallService`/`PhoneAccount`
fix until that's confirmed, since if the constraint is in HFP/firmware, app-side Telecom
changes won't help at all.

### 2. Data import — model gap table

Full end-to-end coverage (watch → parse → store/upload) already exists for: steps/activity,
sleep (incl. V3 REM/SpO2/HR), heart rate, SpO2, stress, HRV, blood pressure, respiratory rate,
skin temperature, body energy, pairing/bind state, and phone→watch notifications.

**Missing entirely:** weather push, alarms, do-not-disturb, device settings (units/week-start),
firmware/OTA info, camera remote, user-profile/step-goal push to watch.

**Explicitly received-but-dropped** (code comments call this out directly at
`IdoSdkWatchEngine.kt:1856-1884`): GPS/routes, workout laps/pace, battery (field exists,
never assigned), find-phone (empty callback), body composition, ECG, ambient noise,
swimming, emotion/mood.

### 3. Weather / phone-locate / pairing / GPS run tracking

- **Weather:** 0% built. No data source, no capability check, no SDK call, no UI. The SDK jar
  fully supports it (`setWeatherDataV3` etc.); veryfit fetches from phone location + its own
  backend, rate-limited to once/hour.
- **Phone locate:** the watch's trigger reaches the app, but `onFindPhone` is a literal empty
  function body (`IdoSdkWatchEngine.kt:1453`), and there is no `WatchControlEvent.FIND_PHONE`
  case to plumb it anywhere. Nothing plays a sound or shows a dialog. veryfit has a full
  dialog + auto-dismiss flow to reference.
- **Pairing:** solid — real bonding-confirmation UI, reconnect/watchdog logic all present. The
  actual gap vs. veryfit is a hardcoded single MAC (`ServiceLocator.kt:54`) with no
  scan-results/device-picker UI. This looks like intentional scope for a one-watch private
  build rather than a bug.
- **GPS/run tracking:** GPS data is received and immediately discarded (diagnostics counter
  only, `IdoSdkWatchEngine.kt:1864-1871`); no live start/stop session, no route storage.
  `WatchWorkout` has no track-point field at all.

### 4. Event reminders

Dashboard already has a full calendar `events`+`reminders` system (RFC5545-ish, cron-dispatched)
plus a simpler `standaloneReminders` table. `dashboard-android` has **no duplicate reminder
code** — it already polls `/api/widget/v1/notifications` (`NotificationWorker.kt`) using the
same device-bearer auth as everything else. The gap: that payload builder
(`lib/intelligence/notifications.ts`) doesn't currently query `standaloneReminders`, and there
is no `REMINDER` kind in the Android `NotificationKind` enum yet.

**Recommendation:** extend the existing feed/worker pattern (add `standaloneReminders` to the
payload, add `REMINDER` to the Android enum) — do not build a parallel reminders system.

### 5. Jared AI

Day-plan generation in `dashboard` is a **deterministic/heuristic pipeline, not an LLM call**
(chat is the only LLM-backed surface today). No TTS/voice code exists for Jared anywhere —
Android's only voice code is speech-to-text for the unrelated Capture text-inbox feature.

Unused data already available for a richer "feed": HRV, workout/training load, habit cadence,
carry-forward tasks, goal-risk flags, calendar-conflict detection (some feed item types are
already defined server-side but not all surfaced).

On the web, Jared is a dedicated `/daily-intelligence` page plus a dashboard teaser card.

**Decision (made 2026-07-02):** Jared AI's UI/logic lives on the **dashboard** (web), including
any future "feed" expansion and AI-voice wiring. `dashboard-android` stays a bridge: it polls
for feed items and posts native notifications with deep links back to the dashboard
(`JaredFeedWorker`/`JaredFeedState`, already built) — it does not get its own Jared screen, and
should not gain one.

### 6. Chat feature gating

Not a flag, not a hardcoded email/ID check. The whole app is single-tenant by construction:
`bootstrap.ts` creates exactly one user row from env vars if the `users` table is empty, and
there is no signup route anywhere in `src/app`. `requireUser()` is fully generic. Opening this
to multiple users means building an invite/signup flow, not removing a gate — the schema
(`userId` FK on every domain row) is already multi-user-shaped.

### 7. Model selection (including Hermes)

Model choice is a DB-backed settings row (`app_settings`), resolved via `resolveChatConfig()`.
Chat fully respects it, including Hermes. Non-chat AI surfaces (day-plan narration,
reflections) intentionally use a **separate** hosted-provider setting and explicitly never
call Hermes/OpenClaw — this is documented in-UI as a deliberate choice, not a bug.
`dashboard-android` has no model-selection code and makes no direct LLM calls (pure REST
client of the dashboard's own API).

### 8. Remote camera

Complete absence on both UI and BLE layers. `ui/capture/` is a same-named but unrelated
text-inbox feature, not camera. The SDK's camera command types (`OPEN_CAMERA`,
`TAKE_ONE_PHOTO`, etc.) arrive at `IdoSdkWatchEngine.kt` and fall into `else -> null`, silently
dropped. No `CAMERA` permission, no Camera2/CameraX code anywhere. veryfit-breakdown has the
full reference: Camera2 pipeline, shutter countdown, ack back to the watch, even live preview
streaming to the watch face.

## Runtime verification note

No phone was reachable over adb during this pass — wireless debugging's IP/port had rotated
(expected after a Wi-Fi flap, see dev-environment notes) and reconnecting wasn't attempted
without checking in first. All findings above are static-analysis-confirmed with file:line
citations; none depended on guessing runtime behavior. Live confirmation (e.g. watching
`onFindPhone` actually fire, capturing a real GPS payload from a sync) should happen before or
during implementation of the relevant workstream, not as a precondition for scoping this doc.

## /goal

Port and build out the watch-integration feature set identified in this discovery pass, as
independent workstreams (not one big "port everything" effort), while keeping
`dashboard-android` scoped strictly to its bridge role (BLE ↔ watch, health upload, feed
polling, native notifications) and putting all product/UI surfaces (Jared AI, feed, voice,
chat) on the `dashboard` web app:

1. **Diagnose call-answer live before fixing anything** — confirm via
   `dumpsys bluetooth_manager` + logcat during a real incoming call whether the constraint is
   HFP/firmware-level (our app's control path is dead code on this watch per hardware-verified
   memory) or actually reachable from app code. If it's HFP-level, an app-side fix may not be
   possible at all and the honest answer may be "can't be fixed here." If it does turn out to
   be reachable, then implement an `InCallService` + `PhoneAccount` (or self-managed
   `ConnectionService`), or request the default-dialer role via `RoleManager`, so
   `acceptRingingCall()` stops silently failing. No reference implementation exists to port
   either way — veryfit-breakdown has the same gap.
2. **Build weather push** — port veryfit's approach: phone location → weather source →
   rate-limited (hourly) push via the SDK's `setWeatherDataV3`/`setWeatherCityName`/
   `setWeatherSunTime`, resent on connect/city-change.
3. **Wire find-phone** — implement `onFindPhone`, add `WatchControlEvent.FIND_PHONE`, add a
   sound/notification/dialog UI, matching veryfit's dialog + auto-dismiss pattern.
4. **Build GPS/run tracking** — live workout start/stop session, route/track-point storage,
   extend `WatchWorkout` (or a new model) to carry a route, upload to the dashboard.
5. **Build remote camera** — Camera2 pipeline, BLE ack back to the watch
   (`replyDeviceStopCameraPreviewRequest`-equivalent), shutter/countdown UI; live preview
   streaming to the watch is a stretch goal, not required for V1.
6. **Extend the reminders bridge** — add `standaloneReminders` to
   `buildNotificationsPayload`/`lib/intelligence/notifications.ts` on the dashboard side; add
   a `REMINDER` kind to the Android `NotificationKind` enum and its notification filter. No new
   worker, no new auth path.
7. **Jared AI feed + voice, on the dashboard** — expand the feed to surface HRV, training
   load, habit cadence, goal-risk, and calendar-conflict insights beyond the day plan; add
   voice (read-aloud and/or voice query) as a dashboard-side capability. `dashboard-android`'s
   role stays limited to notifying + deep-linking into the dashboard; it does not get a
   dedicated Jared screen.
8. **Data import backfill** — from the gap table in Finding 2, pick up alarms, DND, device
   settings, firmware/OTA info, user-profile/step-goal push, and battery wiring as they become
   relevant; treat exotic V3-only metrics (body composition, ECG, noise, swimming, emotion) as
   lowest priority unless the watch model is confirmed to support them.
9. **Chat/model multi-user opening** — deferred until there's an actual plan to deploy beyond
   personal use; when it happens, it's a signup/invite flow, not a flag flip.

## Decisions already made (2026-07-02)

- Jared AI (including any "feed" expansion and AI-voice wiring) lives on the **dashboard** web
  app. `dashboard-android` remains bridge-only: BLE, health upload, feed polling, native
  notifications with deep links.
- `dashboard-android` is a temporary build; it will eventually be converted into a proper
  backend + frontend pair for wider deployment. Scope Android-side work accordingly — don't
  build durable product UI there beyond what the bridge role needs.

## Open decisions deferred to Fable

The following were flagged during discovery and are left for Fable to decide when picking up
each workstream (not blocking on Fabian's input):

- Jared AI feed scope: which of HRV insight, training-load, habit-streak nudges, goal-risk,
  calendar-conflict actually get surfaced, and in what form.
- Jared AI voice scope: read-aloud only (on-device or dashboard-driven TTS) vs. also
  supporting voice query; where the TTS/audio synthesis actually runs.
- Chat multi-user gating: whether/when to build the signup/invite flow; not urgent while the
  app remains personal-use.
- Pairing scope: stay single-hardcoded-MAC vs. build a real device-picker (only matters if a
  second/replacement watch is ever expected).
- Data import priority ordering within Finding 2's gap table: alarms/DND look like the more
  plausible near-term wins; exotic V3 metrics (ECG/body-composition/swimming) probably aren't
  worth it unless the watch model is confirmed to support them.
