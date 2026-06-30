package dev.jaredhq.dashboardandroid.watch.engine

/** Engine-neutral playback state pushed from the phone to the watch. */
enum class WatchPlaybackState {
    PLAYING,
    PAUSED,
    STOPPED,
}

/** Current phone track. Metadata is private user data and must never be written to logs. */
data class WatchNowPlaying(
    val title: String,
    val artist: String,
    val state: WatchPlaybackState,
    val positionSeconds: Int,
    val durationSeconds: Int,
)

/** Music action delivered by the watch over the IDO control callback. */
enum class WatchMusicControlEvent {
    PLAY,
    PAUSE,
    STOP,
    NEXT,
    PREVIOUS,
}

/** Music-related flags from the connected watch's function table. */
data class WatchMusicCapabilities(
    val known: Boolean = false,
    val phoneMusicControl: Boolean = false,
    val artistName: Boolean = false,
    val onboardMusic: Boolean = false,
)

/** Watch-owned storage reported by the onboard-music library query. */
data class WatchMusicStorage(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
)

/** One MP3 reserved in the watch's onboard library. */
data class WatchMusicLibraryItem(
    val musicId: Int,
    val fileName: String,
    val artist: String,
    val sizeBytes: Long,
)

data class WatchMusicFolder(
    val folderId: Int,
    val name: String,
    val musicIds: List<Int>,
)

enum class WatchMusicLibraryStatus { UNAVAILABLE, LOADING, READY, ERROR }

data class WatchMusicLibraryState(
    val status: WatchMusicLibraryStatus = WatchMusicLibraryStatus.UNAVAILABLE,
    val storage: WatchMusicStorage? = null,
    val songs: List<WatchMusicLibraryItem> = emptyList(),
    val folders: List<WatchMusicFolder> = emptyList(),
    val error: String? = null,
)

/** A validated private-cache MP3. The path must never be logged or rendered. */
data class WatchSongImport(
    val privateCachePath: String,
    val firmwareFileName: String,
    val artist: String,
    val sizeBytes: Long,
    val durationMillis: Long? = null,
)

enum class WatchMusicTransferStatus {
    IDLE,
    RESERVING,
    TRANSFERRING,
    CLEANING_UP,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class WatchMusicTransferState(
    val status: WatchMusicTransferStatus = WatchMusicTransferStatus.IDLE,
    val progressPercent: Int? = null,
    val bytesTotal: Long = 0,
    val error: String? = null,
) {
    val active: Boolean
        get() = status == WatchMusicTransferStatus.RESERVING ||
            status == WatchMusicTransferStatus.TRANSFERRING ||
            status == WatchMusicTransferStatus.CLEANING_UP
}

/** SDK-independent query values, kept public so mapping can be exercised on the JVM. */
data class WatchMusicLibraryInput(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val songs: List<WatchMusicLibraryItem>,
    val folders: List<WatchMusicFolder> = emptyList(),
)

fun mapWatchMusicLibrary(input: WatchMusicLibraryInput): WatchMusicLibraryState {
    val songs = input.songs.filter { it.musicId >= 0 && it.sizeBytes >= 0 }
    val songIds = songs.mapTo(mutableSetOf()) { it.musicId }
    return WatchMusicLibraryState(
        status = WatchMusicLibraryStatus.READY,
        storage = WatchMusicStorage(
            totalBytes = input.totalBytes.coerceAtLeast(0),
            usedBytes = input.usedBytes.coerceAtLeast(0),
            freeBytes = input.freeBytes.coerceAtLeast(0),
        ),
        songs = songs,
        folders = input.folders.filter { it.folderId in 1..254 }.map { folder ->
            folder.copy(musicIds = folder.musicIds.distinct().filter { it in songIds })
        },
    )
}

fun hasWatchMusicCapacity(freeBytes: Long, fileBytes: Long): Boolean =
    fileBytes > 0 && freeBytes >= fileBytes

private const val WATCH_MUSIC_EXTENSION = ".mp3"
const val WATCH_MUSIC_FILENAME_LIMIT = 40
const val WATCH_MUSIC_FOLDER_NAME_LIMIT = 18
const val WATCH_MUSIC_FOLDER_LIMIT = 10

/** Produce a watch-safe MP3 name, preserving the extension inside the firmware's 40-char limit. */
fun sanitizeWatchMusicFilename(candidate: String): String {
    val stem = candidate.substringBeforeLast('.', candidate)
        .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .ifBlank { "Song" }
    val maxStem = WATCH_MUSIC_FILENAME_LIMIT - WATCH_MUSIC_EXTENSION.length
    return stem.take(maxStem).trimEnd(' ', '.')
        .ifBlank { "Song" }
        .plus(WATCH_MUSIC_EXTENSION)
}

/** Add a numeric suffix when the sanitized name already exists, without exceeding 40 chars. */
fun uniqueWatchMusicFilename(candidate: String, existingNames: Collection<String>): String {
    val sanitized = sanitizeWatchMusicFilename(candidate)
    val occupied = existingNames.mapTo(mutableSetOf()) { it.lowercase() }
    if (sanitized.lowercase() !in occupied) return sanitized
    val stem = sanitized.removeSuffix(WATCH_MUSIC_EXTENSION)
    for (number in 2..999) {
        val suffix = " ($number)"
        val available = WATCH_MUSIC_FILENAME_LIMIT - WATCH_MUSIC_EXTENSION.length - suffix.length
        val result = stem.take(available).trimEnd() + suffix + WATCH_MUSIC_EXTENSION
        if (result.lowercase() !in occupied) return result
    }
    return sanitized
}

fun sanitizeWatchMusicFolderName(candidate: String): String = candidate
    .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim(' ', '.')
    .take(WATCH_MUSIC_FOLDER_NAME_LIMIT)
    .trimEnd(' ', '.')

fun uniqueWatchMusicFolderName(candidate: String, existingNames: Collection<String>): String {
    val sanitized = sanitizeWatchMusicFolderName(candidate)
    if (sanitized.isBlank()) return ""
    val occupied = existingNames.mapTo(mutableSetOf()) { it.lowercase() }
    if (sanitized.lowercase() !in occupied) return sanitized
    for (number in 2..99) {
        val suffix = " ($number)"
        val result = sanitized.take(WATCH_MUSIC_FOLDER_NAME_LIMIT - suffix.length).trimEnd() + suffix
        if (result.lowercase() !in occupied) return result
    }
    return ""
}

fun nextWatchMusicFolderId(folders: Collection<WatchMusicFolder>): Int? {
    if (folders.size >= WATCH_MUSIC_FOLDER_LIMIT) return null
    val next = (folders.maxOfOrNull { it.folderId } ?: 0) + 1
    return next.takeIf { it in 1..254 }
}

data class WatchMusicFolderUpdatePlan(
    val name: String,
    val desiredMusicIds: List<Int>,
    val additions: List<Int>,
    val retainedAfterRemoval: List<Int>,
    val renameRequired: Boolean,
    val removalRequired: Boolean,
)

fun planWatchMusicFolderUpdate(
    folder: WatchMusicFolder,
    candidateName: String,
    desiredMusicIds: List<Int>,
    validSongIds: Set<Int>,
    otherFolderNames: Collection<String>,
): WatchMusicFolderUpdatePlan? {
    val safeName = uniqueWatchMusicFolderName(candidateName, otherFolderNames)
    val desired = desiredMusicIds.distinct()
    if (safeName.isBlank() || desired.any { it !in validSongIds }) return null
    val desiredSet = desired.toSet()
    val currentSet = folder.musicIds.toSet()
    val retained = folder.musicIds.filter { it in desiredSet }
    return WatchMusicFolderUpdatePlan(
        name = safeName,
        desiredMusicIds = desired,
        additions = desired.filterNot { it in currentSet },
        retainedAfterRemoval = retained,
        renameRequired = safeName != folder.name,
        removalRequired = retained.size != folder.musicIds.size,
    )
}

enum class WatchMusicLibraryMutationStatus {
    IDLE,
    CREATING,
    UPDATING,
    DELETING,
    SUCCEEDED,
    FAILED,
}

data class WatchMusicLibraryMutationState(
    val status: WatchMusicLibraryMutationStatus = WatchMusicLibraryMutationStatus.IDLE,
    val error: String? = null,
) {
    val active: Boolean
        get() = status == WatchMusicLibraryMutationStatus.CREATING ||
            status == WatchMusicLibraryMutationStatus.UPDATING ||
            status == WatchMusicLibraryMutationStatus.DELETING
}

fun canStartWatchMusicOperation(
    connection: WatchEngineConnectionState,
    operationActive: Boolean,
): Boolean = connection == WatchEngineConnectionState.CONNECTED && !operationActive

/** Whether a terminal import must remove the already-reserved watch row. */
fun shouldCleanupReservedSong(reservedMusicId: Int?, succeeded: Boolean): Boolean =
    reservedMusicId != null && !succeeded

/** Clamp SDK progress and never regress when it repeats an earlier packet percentage. */
fun reduceWatchMusicProgress(
    state: WatchMusicTransferState,
    reportedPercent: Int,
): WatchMusicTransferState = state.copy(
    progressPercent = maxOf(state.progressPercent ?: 0, reportedPercent.coerceIn(0, 100)),
)
