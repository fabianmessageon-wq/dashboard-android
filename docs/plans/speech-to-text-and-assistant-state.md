# Android speech-to-text + assistant-state repair plan

## Goal

Make mobile capture feel safe and fast:

1. Add on-device speech-to-text to the Android Capture screen so the user can see/edit the transcript before sending it to the dashboard assistant.
2. Investigate and fix the current assistant capture state, which is not working reliably.
3. Keep server transcription out of scope for this slice unless a future fallback is explicitly needed.

## Product decision

Prefer Android-native speech recognition over sending raw audio to the server:

- Privacy/control: the user sees the recognized text before sending it.
- Scope: no server audio upload endpoint, storage, or AI transcription contract needed.
- UX: tap mic -> dictate -> text appears in the existing input field -> user edits -> sends via Assistant or Task only.

Use Android platform speech recognition:

- `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` via Activity Result, or `SpeechRecognizer` if streaming/partial results are needed.
- Start with the simpler Activity Result approach unless it cannot satisfy Compose lifecycle needs.
- Request/add `android.permission.RECORD_AUDIO` only if the chosen API path requires it on target devices; handle denied/missing recognizer gracefully.

## Current state discovered

Relevant files:

- `app/src/main/java/dev/jaredhq/dashboardandroid/ui/capture/CaptureScreen.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/ui/capture/CaptureViewModel.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`
- API path: `DashboardRepository.chat(message)` -> `POST /api/widget/v1/chat`
- Direct path: `DashboardRepository.capture(title)` -> `POST /api/widget/v1/capture`

Current Capture screen is text-only:

- `OutlinedTextField` for `state.input`
- Assistant / Task-only `FilterChip`s
- Send button calls `CaptureViewModel.send()`
- `lastReply` is a single text card; no durable assistant status/actions/pending-confirmation UI.

Potential assistant-state problems to investigate:

- `send()` reads `_state.value.useAssistant` after setting `sending=true`; likely okay, but race-proof by snapshotting mode/input at send start.
- Assistant result only stores `lastReply`; actions, fallback mode, pending confirmations, created task id, and whether the assistant actually ran are mostly hidden.
- Errors surface as raw `e.message`, likely unhelpful for auth/network/server/502.
- The UI may look like it “doesn’t work” when `/chat` returns task-fallback or no reply, because the state just says `Captured.` or appends `(AI off — saved as task)`.
- Need verify DTO mapping after recent daySummary/agenda contract changes so `/chat` enriched responses decode the fresh Today payload correctly.

## Implementation plan

### 1. Contract/source sanity check first

- Review `CaptureResponseDto`, `Mappers.kt`, `CaptureResult.kt`, `FakeDashboardApiClient.kt`, `RepositoryTest.kt`, and `PayloadMappingTest.kt`.
- Ensure `/chat` responses with new `agenda` + `daySummary` fields decode through `CaptureResponseDto.toTodayDto()` and cache correctly.
- Keep all field names aligned to dashboard producer: `daySummary`, not `dayShape`.

### 2. Repair assistant capture state

Update `CaptureUiState` to carry richer explicit state, for example:

- `lastReply: String?`
- `lastMode: CaptureMode?`
- `lastActions: List<String>`
- `lastCreatedTaskId: Int?`
- `pendingConfirmationCount` or `pendingConfirmation: List<String>` if currently modeled
- optional `statusMessage` for human-readable success state

Update `send()`:

- Snapshot `val text = _state.value.input.trim()` and `val useAssistant = _state.value.useAssistant` before mutation.
- Clear previous result/error at send start.
- On assistant success, display:
  - actual assistant reply when present
  - fallback message when `TASK_FALLBACK`
  - action names/tool summary when present
  - created task id if present
- On direct task success, keep deterministic “Saved as task” behavior.
- Always refresh widget after success.
- Map failures to friendly messages without leaking raw internal details.

Update `CaptureScreen`:

- Show a clearer Assistant result card:
  - “Assistant captured this” vs “AI off — saved as task”
  - reply
  - actions run / task id where useful
- Disable mode toggles while sending.

### 3. Add Android speech-to-text UX

Add a microphone affordance beside or below the text field:

- Button states:
  - “Speak” / mic icon when idle
  - “Listening…” or disabled during recognition launch
  - fallback disabled/error if no recognizer app is available
- Recognition result handling:
  - Insert/replace the input field with transcript, or append if input already has text.
  - Do not auto-send. User must review/edit and tap Capture.
  - Preserve current Assistant/Task-only toggle.
- Permission/error UX:
  - Add `RECORD_AUDIO` permission if needed.
  - If permission denied, show “Microphone permission is needed for dictation. You can still type.”
  - If no speech recognizer is available, show “Speech recognition is not available on this device.”

Suggested simple architecture:

- Keep transcription launcher in `MainActivity`/Capture route composable because Activity Result APIs are UI/lifecycle-level.
- Pass callbacks/flags to `CaptureScreen`:
  - `onStartSpeechInput: () -> Unit`
  - `speechAvailable: Boolean`
  - `speechListening: Boolean` if useful
- When transcript returns, call `vm.onInputChange(transcript)` or a new `vm.applySpeechTranscript(transcript)` that appends intelligently.

### 4. Tests/docs

Add/update source-level tests where possible:

- `CaptureViewModel` unit tests for:
  - assistant success stores reply/actions/mode and clears sending/input
  - task-fallback shows explicit fallback state
  - direct capture still works
  - useAssistant is snapshotted at send start
- DTO mapping tests for `/chat` response carrying `agenda` + `daySummary`.
- README/manual test plan:
  - tap mic -> speak -> transcript appears -> edit -> send Assistant
  - deny mic permission -> typing still works
  - assistant unavailable/fallback -> app shows fallback state clearly

## Verification

Preferred:

- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`

Known environment caveat from this VPS:

- Android toolchain was previously absent (`gradlew`, Gradle binary, wrapper jar, kotlinc/javac unavailable). If still unavailable, report exact blocker and do source-level verification only. Do not fabricate build/test results.

## Acceptance criteria

- User can dictate on Android, review/edit transcript, then send.
- No raw audio is uploaded to Jared HQ/server for this slice.
- Assistant capture state clearly shows whether the assistant ran, fell back, or failed.
- Existing direct capture remains unchanged.
- Widget refresh still happens after successful capture/chat.
- Dashboard contract remains aligned with current producer fields (`agenda`, `daySummary`).

## Final decisions / status (implemented)

**Speech-to-text — Activity Result, not `SpeechRecognizer`.** Chosen
`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` via
`rememberLauncherForActivityResult(StartActivityForResult())`, wired at the
Capture route in `MainActivity`. Rationale: it satisfies the product flow
(dictate → transcript into the field → review/edit → manual send) with the least
surface, and crucially **needs no `RECORD_AUDIO` permission** — the system
recognizer app owns the microphone. So no runtime-permission dance ships in this
slice. (`SpeechRecognizer` would only be needed for in-app partial/streaming
results, which we don't surface.)

- Availability probed with `SpeechRecognizer.isRecognitionAvailable(context)`;
  the button shows **"Speech unavailable"** and is disabled when false.
- Added a `<queries>` entry for `android.speech.RecognitionService` so that
  availability check + intent resolution work under Android 11+ package
  visibility. **No `RECORD_AUDIO`** added (documented inline in the manifest).
- Transcript merges via `CaptureViewModel.applyTranscript` (append when the field
  already has text, else set) — never auto-sends.
- Graceful degradation: no recognizer / launch throws → `onSpeechUnavailable`;
  empty result (cancel/silence) → `onSpeechNoResult`. Both show a neutral notice,
  typing always works.

**Assistant-state repair.** `CaptureUiState` now carries explicit result fields:
`statusMessage` (friendly headline), `lastReply`, `lastMode`, `lastActions`,
`lastCreatedTaskId`, `pendingConfirmation`, plus `speechNotice`. `send()`
snapshots `input` + `useAssistant` before mutating state, clears the prior
result, and on success populates all fields; the screen renders a result card
that distinguishes assistant-ran / task-fallback / needs-confirmation, and mode
chips are disabled while sending. Errors map to friendly, status-aware messages
(auth / unreachable / 502 / 5xx / 400) with no raw internals. Widget refresh
after success is preserved.

**Tests.** `CaptureViewModelTest` (assistant success, snapshot, direct, fallback,
auth/502 error mapping, transcript merge) and a `PayloadMappingTest` case for a
`/chat` response carrying `agenda` + `daySummary`.

**Status:** source + tests written; **not compiled/run** — this VPS has no JDK /
Gradle / Android SDK (`gradlew` absent, `java`/`gradle`/`kotlinc` not on PATH).
Verified by source/static review only.
