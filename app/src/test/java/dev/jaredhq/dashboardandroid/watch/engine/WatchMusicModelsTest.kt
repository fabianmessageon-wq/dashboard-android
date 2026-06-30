package dev.jaredhq.dashboardandroid.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMusicModelsTest {
    @Test
    fun filenameIsSanitizedAndExtensionFitsLimit() {
        val result = sanitizeWatchMusicFilename("  A / very:*long? song name that exceeds firmware.mp3  ")

        assertTrue(result.endsWith(".mp3"))
        assertTrue(result.length <= WATCH_MUSIC_FILENAME_LIMIT)
        assertFalse(result.any { it in "/:*?\"<>|" })
    }

    @Test
    fun blankFilenameGetsSafeFallback() {
        assertEquals("Song.mp3", sanitizeWatchMusicFilename("..."))
    }

    @Test
    fun duplicateFilenameGetsBoundedSuffix() {
        val first = sanitizeWatchMusicFilename("A very long song title that reaches the filename boundary.mp3")
        val second = uniqueWatchMusicFilename(first, listOf(first.uppercase()))

        assertTrue(second.endsWith(" (2).mp3"))
        assertTrue(second.length <= WATCH_MUSIC_FILENAME_LIMIT)
    }

    @Test
    fun playlistNamesAreSanitizedUniqueAndBounded() {
        val sanitized = sanitizeWatchMusicFolderName("  Road / running playlist  ")
        val unique = uniqueWatchMusicFolderName(sanitized, listOf(sanitized.uppercase()))

        assertTrue(sanitized.length <= WATCH_MUSIC_FOLDER_NAME_LIMIT)
        assertFalse('/' in sanitized)
        assertTrue(unique.endsWith(" (2)"))
        assertTrue(unique.length <= WATCH_MUSIC_FOLDER_NAME_LIMIT)
    }

    @Test
    fun nextPlaylistIdHonoursStockTenFolderLimit() {
        assertEquals(4, nextWatchMusicFolderId(listOf(WatchMusicFolder(3, "Three", emptyList()))))
        assertEquals(
            null,
            nextWatchMusicFolderId((1..WATCH_MUSIC_FOLDER_LIMIT).map { WatchMusicFolder(it, "$it", emptyList()) }),
        )
    }

    @Test
    fun playlistUpdatePlanSeparatesRemovalFromAddition() {
        val plan = requireNotNull(
            planWatchMusicFolderUpdate(
                folder = WatchMusicFolder(1, "Old", listOf(1, 2)),
                candidateName = "New",
                desiredMusicIds = listOf(2, 3, 3),
                validSongIds = setOf(1, 2, 3),
                otherFolderNames = emptyList(),
            ),
        )

        assertTrue(plan.renameRequired)
        assertTrue(plan.removalRequired)
        assertEquals(listOf(2), plan.retainedAfterRemoval)
        assertEquals(listOf(3), plan.additions)
        assertEquals(listOf(2, 3), plan.desiredMusicIds)
    }

    @Test
    fun playlistUpdateRejectsUnknownSong() {
        assertEquals(
            null,
            planWatchMusicFolderUpdate(
                WatchMusicFolder(1, "List", emptyList()),
                "List",
                listOf(99),
                validSongIds = setOf(1),
                otherFolderNames = emptyList(),
            ),
        )
    }

    @Test
    fun capacityRequiresPositiveFileThatFits() {
        assertTrue(hasWatchMusicCapacity(freeBytes = 100, fileBytes = 100))
        assertFalse(hasWatchMusicCapacity(freeBytes = 99, fileBytes = 100))
        assertFalse(hasWatchMusicCapacity(freeBytes = 100, fileBytes = 0))
    }

    @Test
    fun operationGateExcludesSyncAndConcurrentWork() {
        assertTrue(canStartWatchMusicOperation(WatchEngineConnectionState.CONNECTED, false))
        assertFalse(canStartWatchMusicOperation(WatchEngineConnectionState.SYNCING, false))
        assertFalse(canStartWatchMusicOperation(WatchEngineConnectionState.CONNECTED, true))
    }

    @Test
    fun failedOrCancelledReservedImportNeedsCleanup() {
        assertTrue(shouldCleanupReservedSong(reservedMusicId = 7, succeeded = false))
        assertFalse(shouldCleanupReservedSong(reservedMusicId = null, succeeded = false))
        assertFalse(shouldCleanupReservedSong(reservedMusicId = 7, succeeded = true))
    }

    @Test
    fun transferProgressIsClampedAndMonotonic() {
        val started = WatchMusicTransferState(
            status = WatchMusicTransferStatus.TRANSFERRING,
            progressPercent = 40,
        )

        assertEquals(40, reduceWatchMusicProgress(started, 20).progressPercent)
        assertEquals(100, reduceWatchMusicProgress(started, 140).progressPercent)
    }

    @Test
    fun libraryMappingClampsStorageAndDropsInvalidSongs() {
        val mapped = mapWatchMusicLibrary(
            WatchMusicLibraryInput(
                totalBytes = 230_686_720,
                usedBytes = -1,
                freeBytes = 230_686_720,
                songs = listOf(
                    WatchMusicLibraryItem(3, "valid.mp3", "Artist", 12),
                    WatchMusicLibraryItem(-1, "invalid.mp3", "", 12),
                ),
                folders = listOf(
                    WatchMusicFolder(1, "Playlist", listOf(3)),
                    WatchMusicFolder(255, "Invalid", emptyList()),
                ),
            ),
        )

        assertEquals(WatchMusicLibraryStatus.READY, mapped.status)
        assertEquals(230_686_720L, mapped.storage?.totalBytes)
        assertEquals(0L, mapped.storage?.usedBytes)
        assertEquals(listOf(3), mapped.songs.map { it.musicId })
        assertEquals(listOf(1), mapped.folders.map { it.folderId })
    }
}
