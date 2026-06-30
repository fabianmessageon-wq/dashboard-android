# Music on watch: SDK findings and Phase A design

Date: 2026-06-29
Target: Fabian's Kogan Active 4 Pro (`F4:91:29:51:C6:45`) only

## Verified SDK and hardware findings

The vendored SDK has complete public entry points for the three planned phases:

- Phone music state to watch: `BLEManager.setMusicControlInfo(MusicControlInfo)`.
  `MusicControlInfo` carries status (`0` invalid, `1` play, `2` pause, `3` stop),
  position/duration in seconds, title, and artist.
- Watch music mode: `setMusicSwitch`, `setMusicSwitchPending`, `enterMusicMode`, and
  `exitMusicMode`.
- Watch-to-phone controls: `DeviceControlAppCallBack` includes `START`, `PAUSE`, `STOP`,
  `NEXT`, `PREVIOUS`, volume up/down, and volume percentage. The stock VeryFit
  `MusicControlService` handles these events through `MediaController`, then falls back to Android
  media-key events. There is no seek event in this SDK callback surface.
- On-watch library query: `queryMusicAndFolderInfo()` returns through
  `OperateCallBack.ICallBack.onQueryResult(MUSIC_AND_FOLDER, value)`, not through
  `IMusicCallBack`.
- Library CRUD: `addMusicFile`, `deleteMusicFile`, folder add/update/delete, and move/remove music
  use `OperateCallBack.IMusicCallBack`.
- File transfer: `FileTransferConfig.getDefaultMusicFileConfig(...)` plus
  `startTranCommonFile(...)` for BLE; equivalent SPP APIs also exist. Progress is reported by
  `IFileTransferListener`.
- MP3 conversion APIs exist (`MP3Tomp3`, `Mp3ToMp3Para`, `Mp3ToPcmPara`, and
  `IMp3ConvertCallBack`). The stock upload path reserves the music entry first, receives its
  `music_id`, then transfers a file whose firmware name matches the reserved name. Its observed
  call graph does not explicitly invoke `MP3Tomp3`, so required codec conversion must be proven
  with a real upload rather than assumed.

Current phone logcat contains the function table emitted for this exact watch:

```text
bleControlMusic=true
V3_music_control_02_add_singer_name=true
V3_support_set_v3_music_name=true
V3_support_v3_ble_music=true
v3_support_get_ble_music_info_version_0x10=true
support_app_connect_with_spp=false
```

It also contains a successful read-only library response:

```text
all_memory=230686720
used_memory=0
useful_memory=230686720
music_num=0
```

This hardware-confirms the onboard library/storage endpoint (220 MiB free) and the watch's
advertised BLE-music capability. A successful file write is still unverified. SPP should not be
selected for this watch because its function table disables app SPP.

The Classic Bluetooth record exposes `AudioSource` and AVRCP (`0x110e`), and Android has its
AVRCP target service enabled. That makes native AVRCP control plausible, but it does not prove a
wrist tap uses AVRCP. The stock VeryFit app also implements the IDO GATT control callback. Phase A
must observe which path this firmware actually uses before dispatching GATT controls, otherwise a
single `NEXT` tap could be applied twice.

## Phase A scope

Phase A provides:

- an opt-in "Now playing on watch" toggle;
- title, artist, playback state, elapsed time, and duration pushed to the watch;
- play, pause, stop, next, and previous control from the watch;
- reconnect/resume behavior without logging track metadata.

Seek is not an unconditional acceptance criterion yet. The SDK has a phone-to-watch elapsed-time
field but no watch-to-phone seek callback. Seek can be included only if the watch's native AVRCP UI
emits a usable seek/fast-forward command during hardware verification.

## App-owned boundary

Add engine-neutral models in `watch/engine/WatchMusicModels.kt`:

```kotlin
data class WatchNowPlaying(
    val title: String,
    val artist: String,
    val state: WatchPlaybackState,
    val positionSeconds: Int,
    val durationSeconds: Int,
)

enum class WatchPlaybackState { PLAYING, PAUSED, STOPPED }
enum class WatchMusicControlEvent { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

data class WatchMusicCapabilities(
    val phoneMusicControl: Boolean,
    val artistName: Boolean,
    val onboardMusic: Boolean,
)
```

Extend `WatchEngine` with engine-neutral members:

```kotlin
val musicControlEvents: SharedFlow<WatchMusicControlEvent>
val musicCapabilities: StateFlow<WatchMusicCapabilities>
fun setPhoneMusicEnabled(enabled: Boolean): Boolean
fun pushNowPlaying(nowPlaying: WatchNowPlaying): Boolean
```

Only `IdoSdkWatchEngine.kt` maps these types to `MusicControlInfo`, SDK capability flags, music
mode calls, and `DeviceControlEventType`. It retains the latest snapshot and resends it after the
function table arrives or the watch reconnects. Disabling sends a stopped/empty state and exits
music mode. Release logs contain only action/state and string lengths.

## Phone media-session bridge

Add an app-owned `watch/music/PhoneMusicController.kt` around
`MediaSessionManager`/`MediaController`:

1. `WatchNotificationListenerService.onListenerConnected()` starts it with this notification
   listener's `ComponentName`; `onListenerDisconnected()` stops it.
2. It observes active sessions, selecting a playing/buffering controller first, then the most
   recently active controller with a non-null playback state.
3. It registers one `MediaController.Callback` on the selected controller and converts metadata and
   playback changes into `WatchNowPlaying`.
4. It pushes only distinct snapshots. Android milliseconds are clamped and converted to seconds;
   the watch can advance elapsed time while state is playing, so no one-second BLE ticker is used.
5. It performs GATT control events through `TransportControls`, with Android media-key fallback
   only when no usable controller exists.

The bridge must not log title, artist, package name, or album. The values remain on the phone and
the user-owned watch.

Persist the toggle in a small app-owned music preferences store, separate from
`dashboard_secure_settings`. The notification-listener grant remains the permission prerequisite;
no new manifest permission is required.

## Control-path rollout

Use a two-step hardware rollout:

1. Push now-playing state and log only the category of incoming IDO music control events. With a
   track playing, test play/pause/next/previous from the watch while observing Android playback and
   the app's callback-category logs.
2. If controls already work and no IDO music callback arrives, classify the path as native AVRCP
   and leave GATT dispatch disabled. If the IDO callback arrives and native playback does not
   change, enable the `PhoneMusicController` dispatch path. If both occur, add event/result
   deduplication before enabling GATT dispatch.

This is the same evidence-first distinction established for call control: the watch's advertised
Classic profile is not sufficient evidence of the runtime path.

## UI and ownership

Add a small music card to the Watch screen:

- toggle, notification-access prerequisite, and current engine capability;
- local now-playing status for confirmation;
- a concise unsupported message if `bleControlMusic=false`.

`WatchHealthViewModel` owns the UI state and toggle event. The composable renders state and emits
events only. The media-session bridge owns Android callbacks; the engine owns SDK calls.

## Tests and acceptance

JVM tests cover pure behavior:

- active-session candidate selection from app-owned snapshots;
- playback-state mapping, millisecond-to-second clamping, and distinct-update suppression;
- music-control event to transport action mapping;
- disabled state never pushes metadata.

Hardware acceptance on the SM-G991B / Android 14:

1. Fresh deploy with `installDebug` (never uninstall or clear data).
2. Force-stop VeryFit before BLE testing and restore normal ownership afterward if needed.
3. With YouTube Music playing, verify title/artist/state/time on the watch.
4. Verify pause/play/next/previous exactly once per wrist tap and record whether the source is AVRCP
   or the IDO callback.
5. Pause, seek from the phone, and change tracks; verify the watch refreshes without a BLE ticker.
6. Disable the toggle and verify metadata/control bridging stops.
7. Disconnect/reconnect and verify the current snapshot is resent.
8. Confirm release-style app logs expose only state/action/lengths, never metadata.

## Deferred Phase B/C gate

Before building the file picker or playlists, add the read-only engine query and reproduce the
230,686,720-byte library response through this app. Then transfer one short, non-sensitive MP3 over
BLE, confirm the required codec behavior and returned `music_id`, play it from the watch to earbuds,
and delete it. Folder/playlists follow only after that lifecycle is reliable.

## Implementation status (2026-06-29)

Phase A is implemented, built, linted, and deployed to the SM-G991B with `installDebug`.

- The Active 4 Pro connected and reported phone control, artist, and onboard-music capabilities.
- The persisted UI toggle enabled IDO music mode after the ordered health sync completed.
- An existing Android media session was discovered and pushed to the watch for stopped, paused,
  and playing state. Verification logs contained only playback state and title/artist lengths.
- Position-only player callbacks are throttled: an eight-second play/pause run produced only the
  playing and paused state pushes. Track/state changes and detected seeks remain immediate; a
  playing track gets a periodic position refresh at most every 15 seconds.
- A wrist-control pass emitted IDO `START`, `PAUSE`, `NEXT`, and `PREVIOUS`. Android showed no
  native AVRCP result during the grace period, so one MediaController fallback was dispatched for
  each event and the resulting playback state was pushed back to the watch. The Active 4 Pro's
  phone-control path is therefore hardware-verified as IDO/GATT, not native AVRCP. Visual
  confirmation of the watch's metadata renderer remains a human check.
- Follow-up testing found two Android media-session edge cases. `PREVIOUS` restarted the current
  track when its position was past three seconds, and the active session did not advertise a
  `NEXT` transport action. Dispatch now checks the session action bitmask: previous first seeks to
  zero and then skips, while unsupported next/previous actions use Android media-key dispatch.
  Transient `SKIPPING_TO_*` playback states also retain the prior watch state instead of being sent
  as `STOPPED`. JVM coverage was added for these dispatch decisions.
- The correction is built, linted, and deployed. Previous still needs a human wrist check, and next
  needs a playlist/album with another queued item; the session used during diagnosis had no next
  queue item, so no implementation can advance it. This queued-track test is a Phase A release gate,
  but does not block the read-only Phase B library query.
- Watch-originated seek remains unsupported by the exposed IDO callback. Phone-originated seeks
  update the elapsed time sent to the watch.
