# Music on watch: Phase B onboard-song plan

Date: 2026-06-29

Target: Fabian's Kogan Active 4 Pro (`F4:91:29:51:C6:45`) only

Prerequisite: Phase A in `music-on-watch-phase-a-design.md`

## Outcome and boundaries

Phase B imports a local MP3 from the phone into the watch's onboard storage, reports transfer
progress, lists the resulting watch library, and deletes a song. The first implementation is BLE
only because this watch reports `support_app_connect_with_spp=false`.

Playlists/folder editing is Phase C. Arbitrary audio codecs, cloud music, dashboard/VPS changes,
background transfer resumption after process death, and SPP transfer are not Phase B work. Track
metadata and audio remain local to the phone and watch and must not appear in release logs.

## Hardware and SDK evidence

This watch reports `V3_support_v3_ble_music=true` and a successful library query returned:

```text
all_memory=230686720
used_memory=0
useful_memory=230686720
music_num=0
```

The vendored IDO SDK exposes the required public surface:

- `BLEManager.queryMusicAndFolderInfo()` returns `MusicOperate.MusicAndFolderInfo` through
  `OperateCallBack.ICallBack.onQueryResult(OperateType.MUSIC_AND_FOLDER, value)`.
- `BLEManager.addMusicFile(...)` and `deleteMusicFile(...)` report through
  `OperateCallBack.IMusicCallBack`.
- `FileTransferConfig.getDefaultMusicFileConfig(path, listener)` and
  `BLEManager.startTranCommonFile(config)` perform BLE transfer with
  `IFileTransferListener` progress.
- `BLEManager.MP3Tomp3(...)`, `Mp3ToMp3Para`, `Mp3ToPcmPara`, and
  `GpsCallBack.IMp3ConvertCallBack` exist, but the stock upload call graph does not explicitly
  invoke conversion. Codec conversion must therefore be decided by a real transfer result.

The stock VeryFit sequence is the implementation oracle: reserve a `MusicFile` with name, artist,
and byte size; receive its `music_id`; transfer a file whose `firmwareSpecName` is the same
filename; then optionally add that ID to a folder. The observed stock filename limit is 40
characters including its extension.

Read-only oracle files (do not copy SDK types above the engine boundary):

- `C:\Users\davo\Documents\Claude\veryfit-breakdown\veryfit-decompiled\sources\com\ido\life\transmitter\task\MusicUploadTask.java`
- `C:\Users\davo\Documents\Claude\veryfit-breakdown\veryfit-decompiled\sources\com\ido\life\transmitter\task\MusicTransferTask.java`
- `C:\Users\davo\Documents\Claude\veryfit-breakdown\veryfit-decompiled\sources\com\ido\life\data\cache\MusicDataManager.java`
- `C:\Users\davo\Documents\Claude\veryfit-breakdown\veryfit-decompiled\sources\com\ido\ble\file\transfer\FileTransferConfig.java`
- `C:\Users\davo\Documents\Claude\veryfit-breakdown\veryfit-decompiled\sources\com\ido\ble\BLEManager.java`

## App architecture

Keep all `com.ido.*` and `com.veryfit.*` references in
`watch/engine/IdoSdkWatchEngine.kt`. Extend `watch/engine/WatchMusicModels.kt` with app-owned models
for storage, library items, import requests, and transfer state. Extend `watch/engine/WatchEngine.kt`
with an engine-neutral surface similar to:

```kotlin
val watchMusicLibrary: StateFlow<WatchMusicLibraryState>
val watchMusicTransfer: StateFlow<WatchMusicTransferState>
fun refreshWatchMusicLibrary(): Boolean
fun importWatchSong(song: WatchSongImport): Boolean
fun cancelWatchSongImport()
fun deleteWatchSong(musicId: Int): Boolean
```

`IdoSdkWatchEngine.kt` will register the query and music-operation callbacks, map SDK values to
app models, and unregister callbacks with engine lifecycle. It will own a single serialized music
operation state machine. Query, reserve, transfer, cleanup, and delete must not overlap one another
or the existing health-sync command stream. A disconnect or explicit cancellation terminates the
active operation and produces an app-owned error/cancelled state.

The Activity uses `ActivityResultContracts.OpenDocument` to select `audio/mpeg` (with `audio/*` as
a compatibility fallback). The selected content URI is copied to a private cache file because the
SDK requires a filesystem path. Extract title, artist, duration, and size with Android media APIs;
sanitize and truncate the firmware filename to 40 characters including `.mp3`. Validate a positive
file size and sufficient `useful_memory` before reserving the entry. Delete temporary files after
success, failure, or cancellation.

`WatchHealthViewModel.kt` owns picker/result and UI state. `WatchHealthScreen.kt` renders storage,
library rows, progress, retry/cancel, and delete confirmation. `AndroidWatchMusicController.kt`
continues to own phone media sessions only; it must not import SDK transfer types. Wire ownership
through the existing `ServiceLocator.kt` and `AppViewModelFactory.kt` patterns.

## Implementation slices

### 1. Read-only library slice

Add the engine models, query callback, operation serialization, and a refresh action. Reproduce the
230,686,720-byte response through this app after reconnect. This proves callback registration and
mapping before any write. Unit-test SDK-independent mapping inputs through app-owned helper values,
not SDK classes.

### 2. One-file transfer proof

Use a short, non-sensitive MP3:

1. Copy and validate the selected file in private cache.
2. Call `addMusicFile` with the final filename, artist, and byte size.
3. Capture the returned `music_id` only from a successful `onAddMusic` callback.
4. Build the default music `FileTransferConfig`, set `firmwareSpecName` to the exact reserved
   filename, and start BLE common-file transfer.
5. Emit progress through the engine-neutral transfer flow.
6. Refresh the library after success.
7. If reservation succeeds but transfer fails or is cancelled, best-effort delete the reserved
   entry and refresh the library so no ghost row consumes storage.

First try the MP3 unchanged, matching the stock upload path. If the watch rejects it, capture only
the SDK error category/code, inspect the MP3 conversion signatures with `javap`, and add conversion
inside `IdoSdkWatchEngine.kt`. Do not pre-emptively transcode or assume PCM: it increases transfer
size and introduces a second failure path without hardware evidence.

### 3. Product UI and deletion

Add the file picker, free/used storage, song list, determinate progress where available, cancel, and
delete confirmation. Disable imports during health sync, an active transfer, or a disconnected
state. A failed operation remains retryable without retaining the original content permission or
cache file indefinitely; retry asks for the file again if the cached copy was cleaned up.

### 4. Reliability pass

Exercise disconnect, watch sleep, low-capacity rejection, duplicate/sanitized names, cancellation,
and transfer failure. Refresh the library after every terminal mutation. Confirm now-playing and
periodic health sync still function after transfer and that a reconnect shows the persisted watch
library. Only after this lifecycle is reliable should Phase C add folder CRUD and playlist UI.

## Tests and acceptance

JVM tests should cover filename sanitizing/truncation, capacity checks, transfer-state reduction,
operation exclusion, cancellation cleanup decisions, and SDK-independent library mapping. Existing
Phase A music-control tests remain green. No test outside `IdoSdkWatchEngine.kt` may import SDK
classes.

Hardware acceptance on the SM-G991B / Active 4 Pro:

1. Complete the Phase A queued-track next/previous check with an album or playlist.
2. Query the empty library and confirm approximately 220 MiB free.
3. Import one short MP3 over BLE and observe monotonic progress without metadata in logs.
4. Confirm the item appears after a fresh query and survives phone/watch reconnect.
5. Play the file on the watch through paired earbuds (the watch is the A2DP source).
6. Delete it and confirm the library count and free storage recover.
7. Interrupt a second transfer and confirm no ghost library item remains.
8. Run a health sync and phone now-playing control afterward to detect SDK state interference.

## Important tools and commands

Run from `dashboard-android` in PowerShell.

Inspect the vendored SDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "$env:JAVA_HOME\bin\jar.exe" tf app/libs/ido-watch-sdk.jar | Select-String -Pattern "Music|Mp3|FileTransfer"
& "$env:JAVA_HOME\bin\javap.exe" -classpath app/libs/ido-watch-sdk.jar <fully.qualified.ClassName>
```

Build and deploy without erasing app configuration:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat installDebug
```

Never use `adb uninstall` or `pm clear`. Before exclusive BLE testing, force-stop VeryFit and the
app, then start the app normally. The watch must be awake for discovery:

```powershell
$adb = "C:\Users\davo\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s 192.168.20.102:33085 shell am force-stop com.watch.life
& $adb -s 192.168.20.102:33085 shell am force-stop dev.jaredhq.dashboardandroid
& $adb -s 192.168.20.102:33085 shell monkey -p dev.jaredhq.dashboardandroid 1
```

Use app-scoped logcat tags such as `IdoSdkWatchEngine`, `WatchConnService`, and a new
`WatchMusicTransfer` tag. Log operation state, progress, byte counts, and SDK error codes only;
never log filenames, titles, artists, URI values, or raw file content. If wireless adb has rotated,
ask Fabian for the current LAN IP address and port. Tailscale and the dashboard/VPS are not needed
for this phase.

## Implementation and hardware result (2026-06-29)

Phase B is implemented and deployed to the SM-G991B / Active 4 Pro. Hardware results:

- The automatic post-sync query reproduced `all/free=230686720`, `used=0`, `music_num=0`.
- A 10,940-byte silent MP3 transferred unchanged over BLE. Progress was monotonic
  (`22, 44, 66, 88, 99, 100`), the reservation returned `music_id=1`, and the follow-up query
  reported one song and exactly 10,940 bytes used.
- A process restart/reconnect returned the same one-song library, proving watch persistence.
- The SDK's native delete implementation crashes if `music_name` is null even though the public API
  accepts a `MusicFile`. The engine therefore passes the complete queried/reserved model (id, name,
  artist, and size) for normal deletion and rollback. Hardware deletion then returned success and
  restored the full free-space count without restarting the process.
- A larger silent transfer was cancelled at 92%. Reserved-row cleanup returned success and the
  follow-up query returned zero songs, zero used bytes, and 230,686,720 free bytes.
- A health sync completed before each automatic library query and phone music mode reapplied after
  transfer, so those command streams remained serialized during the exercised paths.

Still manual: play an imported song from the watch through paired earbuds, repeat the Phase A
queued-track next/previous check, and explicitly exercise phone now-playing control after a transfer.

## Key files

- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/WatchEngine.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/WatchMusicModels.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/engine/IdoSdkWatchEngine.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/watch/music/AndroidWatchMusicController.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/ui/watch/WatchHealthViewModel.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/ui/watch/WatchHealthScreen.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/di/ServiceLocator.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/ui/AppViewModelFactory.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/work/WatchConnectionService.kt`
- `app/src/main/java/dev/jaredhq/dashboardandroid/notify/WatchNotificationListenerService.kt`
- `docs/plans/music-on-watch-phase-a-design.md`
