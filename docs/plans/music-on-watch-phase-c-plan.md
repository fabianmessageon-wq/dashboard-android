# Music on watch: Phase C playlist plan

Date: 2026-06-29

Target: Fabian's Kogan Active 4 Pro (`F4:91:29:51:C6:45`) only

Prerequisite: the hardware-verified Phase B lifecycle in `music-on-watch-phase-b-plan.md`

## Outcome and boundaries

Phase C adds watch-local playlist management: create, rename, delete, and edit the membership of
onboard songs. Playlists remain local to the phone/watch. Reordering songs within a playlist,
phone-side playlists, cloud metadata, and folder-aware import are deferred until the watch proves
it preserves and exposes an order distinct from `music_index`.

## SDK and stock-app rules

- `MusicOperate.MusicFolder` carries `folder_id`, `folder_name`, `music_index`, and `music_num`.
- The stock app assigns a new ID as the current maximum folder ID plus one.
- Stock constants limit the library to 10 folders and folder names to 18 characters.
- `addMusicFolder` creates an empty folder; `updateMusicFolder` renames it;
  `deleteMusicFolder` removes the folder without deleting its songs.
- `moveMusicIntoFolder` receives only the IDs being added.
- `removeMusicFromFolder` receives the complete membership that should remain.
- Folder callbacks are `onAddFolder`, `onModifyFolder`, `onDeleteFolder`, `onImportFolder`, and
  `onDeleteFolderMusic` respectively.

All SDK types remain inside `IdoSdkWatchEngine.kt`. Folder changes share the Phase B serialized
music-operation gate, cannot overlap health sync or file transfer, have command timeouts, and always
refresh the full library after success, failure, or a partially completed multi-step edit.

## Acceptance

1. Query an empty library and create an empty playlist.
2. Rename it and confirm the name survives reconnect.
3. Import two non-sensitive MP3 fixtures, add both, remove one, and verify query membership each time.
4. Delete the playlist and confirm both songs remain in the root library.
5. Delete the fixtures and confirm storage returns to its baseline.
6. Run health sync and phone music control afterward.

The physical queued-track, earbuds playback, and explicit post-transfer now-playing checks remaining
from Phases A/B are still manual acceptance items; adb must not choose or play private phone media.

## Implementation and hardware result (2026-06-30)

Phase C is implemented and hardware-verified on the SM-G991B (Android 14) and Kogan Active 4 Pro.
The current debug APK (`dev.jaredhq.dashboardandroid`, version `0.1.0` / code `1`) was deployed with
`JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` and `./gradlew.bat installDebug` over
wireless adb discovered through mDNS (`adb-R5CR20XQLRW-1R6oZR._adb-tls-connect._tcp`). VeryFit was
force-stopped before the BLE session.

Hardware acceptance results:

- The starting query returned no songs or folders and the exact Phase B baseline:
  `used=0`, `free=230686720`.
- Creating an empty playlist returned success with `folder_id=1`. Renaming it to `PhaseC2`,
  force-stopping/restarting the app, reconnecting, and querying again preserved the new name.
- Two non-sensitive 8,558-byte AndroidX sine-wave MP3 fixtures transferred unchanged over BLE as
  `music_id=1` and `music_id=2`. Both transfers reported monotonic progress
  (`28, 56, 84, 99, 100`); the query then returned `music_num=2`, `used=17116`, and
  `free=230669604`.
- Adding both songs to the playlist refreshed the UI to two members. Removing one refreshed it to
  one member while the root library remained at two songs.
- Deleting the playlist returned success, removed the folder, and left both songs and all 17,116
  used bytes in the root library.
- Deleting both fixtures returned the watch to `music_num=0`, `used=0`, and
  `free=230686720`. The two temporary phone `Download` fixtures were also removed.
- A health sync started and completed successfully after all playlist operations. Phone music mode
  was then toggled off and on, and both commands completed (`enabled=false`, then `enabled=true`).

No private phone media was selected or played. App logs contained operation state, numeric IDs,
progress, and byte counts only. The older physical queued-track, earbuds playback, and active-track
post-transfer now-playing checks from Phases A/B remain manual and are not Phase C blockers.
