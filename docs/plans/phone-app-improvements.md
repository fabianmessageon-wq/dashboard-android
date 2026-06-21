# Phone app improvements implementation plan

## Goal

Improve the phone experience without turning the dashboard into a desktop-only control panel. This slice covers five user-facing changes:

1. Let a user mark a task as **In progress** when they are actively working on it.
2. Keep active goals focused by moving completed goals into a separate **Completed Goals** section at the bottom of the Goals page.
3. Make the Today workflow show multiple relevant tasks/events when useful, and make task selection/ranking more strongly reflect the priority of each task's related goal.
4. Let the Workout page plan full workouts, not only log ad-hoc sessions, with simple recurring schedules such as "repeat every X days".
5. Add a visible mobile-friendly way to show chat tool/skill details; do not rely only on the desktop Ctrl+O shortcut.

## Product principles

- Phone first: every new control must be reachable by touch with a visible label/icon, not keyboard shortcuts or hover-only affordances.
- Keep the current data model stable where possible. Prefer additive columns/tables and compatibility mappers over rewriting existing task/workout history.
- Today should be a small planning surface, not a dumping ground. Show the top few relevant items and an agenda preview; link out for full lists.
- Preserve source ownership: tasks own work items, events own calendar time blocks, workout plans own workout templates/schedules, and completed historical workouts remain immutable logs.

## Current state to verify before coding

Dashboard web repo paths:

- Tasks
  - `src/lib/db/schema.ts` — `tasks` has `completed`/`completedAt`, but no active/in-progress state.
  - `src/app/(app)/tasks/page.tsx`
  - `src/app/(app)/tasks/task-card.tsx`
  - `src/app/(app)/goals/actions.ts` — current task create/toggle/delete/delay/schedule actions.
  - `src/app/(app)/dashboard/today-section.tsx` — Dashboard "Today's tasks" card currently lists open tasks due today/overdue/no due date.
- Goals
  - `src/lib/db/schema.ts` — `goals.status` already includes `completed` and `completedAt`.
  - `src/app/(app)/goals/page.tsx` — currently queries non-archived goals together and sorts by status/priority/target date.
  - `src/app/(app)/goals/actions.ts`
- Today/intelligence
  - `src/lib/intelligence/context.ts`
  - `src/lib/intelligence/nextBestAction.ts` — ranks tasks with weighted factors, including goal priority, but outputs a single primary task through `buildDayPlan`.
  - `src/lib/intelligence/plan.ts` — `DayPlan.mainTask` and `focusBlock` are single-item oriented.
  - `src/lib/intelligence/widgetPayload.ts` and `src/app/api/widget/v1/**` — Android/widget contract currently centers on `mainAction`, with additive agenda/day-summary work already documented.
  - `tests/unit/intelligence-plan.test.ts`, `tests/unit/intelligence-widget-payload.test.ts`
- Workouts
  - `src/lib/db/schema.ts` — `workouts` and `workoutSets` are historical logs; no workout template/plan tables.
  - `src/app/(app)/workouts/page.tsx`
  - `src/app/(app)/workouts/actions.ts`
  - `src/app/(app)/workouts/[id]/page.tsx`
  - `src/lib/data/workouts.ts`
- Tool/skill details
  - `src/app/(app)/chat/chat-client.tsx` — Ctrl+O toggles expanded tool details through `toolDetailsExpanded`; no visible mobile affordance.

Android/phone docs and client paths:

- `dashboard-android/docs/api-contract.md` — Today payload contract for `/api/widget/v1/today`.
- `dashboard-android/docs/roadmap.md` — phone implementation roadmap.
- `dashboard-android/app/src/main/**` — client DTO/domain/UI mapping must remain lenient for additive Today fields.

## Proposed implementation slices

### Slice 1 — Task "In Progress" state

#### Data model

Add an additive task field rather than replacing `completed` immediately:

- `tasks.inProgressAt: integer timestamp nullable`

Derived task state:

- `completed === true` -> completed
- `completed === false && inProgressAt != null` -> in progress
- `completed === false && inProgressAt == null` -> todo

Why this shape:

- Existing code and tests that key off `completed` keep working.
- Completion remains a terminal state unless the task is reopened.
- It supports a simple "started at" timestamp for sorting and Today emphasis.

Implementation notes:

- Add schema column and migration/repair if this repo uses explicit migrations for deployed SQLite.
- Add server actions in `src/app/(app)/goals/actions.ts`:
  - `startTaskAction(taskId)` sets `inProgressAt = now` for that user's open task.
  - `stopTaskAction(taskId)` clears `inProgressAt` for that user's open task.
  - `toggleTaskAction` must clear `inProgressAt` when marking completed, and may leave it null when reopening.
- Decide whether the app allows multiple in-progress tasks. Recommended V1: allow multiple but visually sort active ones first; do not add global single-active constraints until the product actually needs it.

#### UI

- `TaskCard` shows a visible button/chip:
  - Todo/open task: "In progress" with a play/clock icon.
  - In-progress task: "Working" / "Stop" with highlighted styling.
  - Completed task: no in-progress button.
- Sort in-progress tasks above other open tasks within each section, then due date/priority order.
- Add in-progress badge to task cards in Goals and Dashboard Today.
- Update optimistic/transition UX so tapping the button is fast on mobile.

#### Tests

- Unit/action tests:
  - Starting an open task sets `inProgressAt`.
  - Stopping clears it.
  - Completing a task clears it and sets `completedAt`.
  - User cannot mutate another user's task.
- UI/source verification:
  - Task cards render the button only for open tasks.
  - In-progress tasks sort ahead of other open tasks.

Acceptance criteria:

- A phone user can mark a task currently being worked on with a visible tap target.
- Completing the task removes the in-progress state.
- Existing task completion behavior remains unchanged.

### Slice 2 — Completed Goals section at bottom

#### Query/partition

In `src/app/(app)/goals/page.tsx`:

- Continue fetching all non-archived goals.
- Partition into:
  - `activeGoals = status !== "completed"`
  - `completedGoals = status === "completed"`
- Render active/paused goals in the current main Goals list.
- Render completed goals in a separate section after active goals, habits, and unlinked habits unless UX wants it immediately after active goals. Recommended: bottom of page, clearly labelled "Completed Goals".

#### Sorting

- Active/paused: existing sort by status, priority high->medium->low, target date.
- Completed: sort by `completedAt desc`, then `targetDate desc`, then `id desc`.

#### UI

- Completed cards should be visually calmer:
  - Muted border/text.
  - Keep description/progress visible for reflection.
  - Hide/soften add-task controls by default; a completed goal should not invite new work unless reopened.
  - Keep a visible "Reopen" or status dropdown so mistakes are reversible.
- Add empty-state copy only when there are no active goals, not because completed goals exist.

#### Tests

- Page/source test or component extraction test for partitioning.
- Action test that completing a goal sets `completedAt`, and reopening clears or leaves it per existing convention. Recommended: clearing `completedAt` on reopen avoids stale "completed on" dates.

Acceptance criteria:

- Completed goals no longer compete with active goals at the top.
- Completed goals are still available for review at the bottom.
- Reopening a completed goal is still possible.

### Slice 3 — Today workflow: multiple relevant tasks/events + priority-aware weighting

This is the most important cross-surface slice because it touches dashboard, Android/widget payloads, and intelligence tests.

#### Product behavior

Today should show:

- A **primary next task** when one task clearly deserves the spotlight.
- A short **Relevant tasks** list when multiple tasks are useful today, ideally 3–5 max:
  - in-progress tasks first,
  - overdue/due-today tasks,
  - high-priority-goal tasks,
  - tasks that fit open free slots,
  - optionally the reflection commitment from last night when it maps to a task.
- A short **Agenda / events** list for today's calendar commitments:
  - next 2–3 events on compact surfaces,
  - expandable/full list on dashboard.

#### Ranking changes

`src/lib/intelligence/nextBestAction.ts` already has `goalPriority`, but make priority more decisive and easier to reason about:

- Add explicit priority multipliers or stronger normalized weights:
  - high goal: 1.0
  - medium goal: 0.65
  - low goal: 0.25
  - no goal: 0.15
- Increase or parameterize the goal-priority contribution so stale low-priority tasks cannot outrank fresh high-priority tasks by accident.
- Add an in-progress boost once Slice 1 lands:
  - in-progress open tasks should almost always appear in Relevant tasks.
  - Do not always force them as the single main task if they are stale/low-value; show both "Working" and "Recommended next" if needed.
- Keep due-date urgency strong enough that a truly overdue low-priority task can still surface, but explain why.

Recommended API shape inside intelligence:

- Extend `DayPlan`:
  - keep `mainTask: RankedTask | null` for backward compatibility.
  - add `relevantTasks: RankedTask[]` capped to 3–5.
  - add `agenda: TodayAgendaItem[]` if not already carried by context/widget payload.
- Keep `mainTask = relevantTasks[0] ?? null` unless the reflection commitment pins another task.

#### Event/agenda handling

- Reuse calendar occurrence expansion/free-busy code; do not query raw recurring `events` rows directly if a projection helper already exists.
- Add lightweight agenda items to `DayContext` or compose them in `widgetPayload.ts`:
  - title
  - start/end/timeLabel
  - href
  - source
  - busy/free
- Dashboard Today should show the list beside/above relevant tasks.
- Android `/api/widget/v1/today` should treat agenda/relevant tasks as additive fields so older clients keep working.

#### Widget/API contract

Additive contract proposal for `/api/widget/v1/today`:

```jsonc
{
  "mainAction": { "title": "Write proposal", "taskId": 12, "href": "/tasks" },
  "relevantTasks": [
    {
      "title": "Write proposal",
      "detail": "High-priority goal · fits 45m",
      "href": "/tasks",
      "taskId": 12,
      "score": 91,
      "goalPriority": "high",
      "inProgress": false
    }
  ],
  "agenda": [
    {
      "title": "Standup",
      "timeLabel": "09:00–09:15",
      "href": "/calendar",
      "busy": true
    }
  ]
}
```

Android handling:

- DTO defaults `relevantTasks = []`, `agenda = []`.
- Today screen shows:
  - Day plan/agenda preview.
  - Recommended tasks list, not only one `mainAction`.
- Widget stays compact:
  - primary action + next event or `+N more`.
  - Do not overload the Glance widget with 5 cards.

#### Tests

- `rankTasks` tests:
  - high-priority goal task beats otherwise similar low-priority goal task.
  - stale low-priority task does not outrank fresh high-priority work solely from age.
  - overdue low-priority task can still surface with an overdue reason.
  - in-progress task appears in `relevantTasks`.
- `buildDayPlan` tests:
  - returns multiple relevant tasks when available.
  - preserves `mainTask` compatibility.
  - reflection commitment still pins safely.
- Widget payload tests:
  - emits additive `relevantTasks` and `agenda`.
  - omits/private-safe fields; no task notes or private content leak into widget payload.

Acceptance criteria:

- Today can show more than one useful task/event on phone and dashboard.
- Goal priority materially changes task selection, and the reason is visible.
- Existing clients that only read `mainAction` continue working.

### Slice 4 — Workout planning with recurring schedules

#### Data model

Keep historical workout logs separate from planned workout templates.

Add new tables:

- `workout_plans`
  - `id`
  - `userId`
  - `title`
  - `notes`
  - `active` boolean default true
  - `repeatEveryDays` integer nullable or not null default 0
  - `startDate` text `YYYY-MM-DD`
  - `nextDueDate` text nullable
  - `createdAt`, `updatedAt`
- `workout_plan_exercises`
  - `id`
  - `planId`
  - `exerciseId`
  - `position`
  - `targetSets`
  - `targetReps` text or `minReps`/`maxReps`
  - `targetWeight` real nullable
  - `notes`

Optional later table, not required for V1:

- `workout_plan_completions` if a single logged workout can satisfy a plan without directly linking `workouts.planId`.

Recommended V1 link:

- Add `workouts.planId` nullable FK to `workout_plans.id`.
- Starting from a plan creates a normal `workouts` row linked to the plan and pre-populates/display-targets on the workout detail page.

#### Scheduling behavior

- `repeatEveryDays = null/0` means no automatic recurrence.
- For a recurring plan:
  - `nextDueDate` starts at `startDate`.
  - When a plan-linked workout is completed, advance `nextDueDate` by `repeatEveryDays` until it is after the completed workout date.
  - If a user skips/reschedules, update `nextDueDate` directly.
- Keep this simpler than full RRULE for V1. Calendar already has recurrence logic, but workout recurrence is product-level cadence and can remain an interval-days field until weekly/by-day requirements appear.

#### UI

On `src/app/(app)/workouts/page.tsx`:

- Add **Workout Plans** card above history:
  - upcoming plan name and next due date,
  - repeat cadence, e.g. "every 3 days",
  - "Start planned workout" button,
  - "Edit plan" / "Skip to next" actions.
- Add create/edit plan form:
  - title,
  - notes,
  - repeat every X days,
  - start date,
  - exercise rows with set/rep targets.
- On workout detail page:
  - Show plan targets above the log-set form.
  - Let actual sets differ from targets; history records what happened, not what was planned.

Today integration:

- Body action should prefer due workout plans:
  - If a workout plan is due today/overdue and readiness is not recovery mode, body action title becomes the plan name.
  - If recovery mode, keep "Take it easy" and show the due workout as deferrable, not guilt-inducing.
- Relevant tasks/events should not treat workout plans as tasks unless/until a task is explicitly created.

Tests:

- Plan recurrence advancement:
  - every 3 days from 2026-06-01, completed 2026-06-01 -> next 2026-06-04.
  - completed late on 2026-06-06 -> next 2026-06-07 or 2026-06-09 depending chosen policy. Recommended policy: advance from previous due date until future (`06-07`) so late completion does not permanently drift the cadence.
- Starting a planned workout links `workouts.planId` and keeps historical logging intact.
- Today body action chooses due plan when appropriate.

Acceptance criteria:

- User can define a full workout plan with exercises/targets.
- User can set a simple recurrence like every 3 days.
- Due planned workout appears on Workouts and Today.
- Completing a planned workout advances the next due date predictably.

### Slice 5 — Mobile-visible tool/skill details toggle

#### Current issue

`src/app/(app)/chat/chat-client.tsx` supports Ctrl+O to expand full tool/skill details. Phone users cannot discover or trigger this.

#### UI options

Recommended V1:

- Add a visible button in the chat header or composer action row:
  - Label: "Tool details" or "Details"
  - Icon: list/search/terminal icon if available.
  - State: pressed/on when `toolDetailsExpanded` is true.
- Also add a compact per-message disclosure when a message has tool calls:
  - "Show tool details" / "Hide tool details"
  - This can toggle the same global state initially, or a per-message override later.

Implementation notes:

- Keep Ctrl+O as a desktop shortcut.
- Use `aria-pressed` on the button.
- The button should appear only when tool calls exist in the current thread, or be disabled with explanatory title when none exist.
- Avoid hover-only tooltips for the mobile path.
- Keep expanded details wrapped/preformatted as they are today, but verify narrow-width wrapping does not overflow.

Tests/manual verification:

- Mobile viewport: button visible and tappable.
- Tapping expands tool invocation details without keyboard.
- Ctrl+O still works on desktop.
- Long args/results wrap and do not horizontally break the chat layout.

Acceptance criteria:

- Phone users can view tool and skill details from a visible control.
- Desktop shortcut remains available.
- The details state is understandable to screen readers.

## Suggested execution order

1. Mobile tool-details button — small, isolated, immediately improves phone usability.
2. Completed Goals section — low schema risk and clear UX improvement.
3. Task In Progress — one additive column + visible task controls.
4. Today multi-task/agenda ranking — depends on task state and touches dashboard + phone API contract.
5. Workout plans/recurrence — largest schema/UI addition; implement after the smaller state/ranking foundations are stable.

## Verification checklist

Run from `dashboard/` when the toolchain is available:

- `npm run lint`
- `npx tsc --noEmit`
- Targeted unit tests:
  - `npm test -- tests/unit/intelligence-plan.test.ts`
  - `npm test -- tests/unit/intelligence-widget-payload.test.ts`
  - add task/workout action tests if this repo has a server-action test harness.
- Manual mobile checks using browser devtools or a real phone:
  - task card in-progress tap target,
  - goals page completed section at bottom,
  - Today shows multiple tasks/events,
  - Workout Plans create/start/complete flow,
  - Chat tool details visible without Ctrl+O.

Run from `dashboard-android/` when Android tooling is available:

- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- Manual device/simulator smoke:
  - Today screen decodes older payload without `relevantTasks`.
  - Today screen decodes new payload with `relevantTasks` + `agenda`.
  - Widget remains compact and does not crash on added fields.

Known VPS caveat: previous Android work in this worktree was source-reviewed only because JDK/Gradle/Android SDK were absent. Do not report Android build/test success unless those commands really run.

## Open decisions for implementation owner

- Should in-progress be single-task-per-user or allow multiple? Recommended V1: allow multiple, sort them first.
- Should reopening a completed goal clear `completedAt`? Recommended: yes, to avoid stale completion dates.
- Should workout recurrence advance from previous due date or actual completion date? Recommended: previous due date until future, so late completions do not permanently drift cadence.
- Should `relevantTasks` expose scores to the Android client? Recommended: include score for debugging/transparency, but UI should show human reasons rather than raw numbers by default.
