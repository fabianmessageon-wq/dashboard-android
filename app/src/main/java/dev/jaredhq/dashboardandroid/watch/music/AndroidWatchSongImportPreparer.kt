package dev.jaredhq.dashboardandroid.watch.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dev.jaredhq.dashboardandroid.watch.engine.WatchSongImport
import dev.jaredhq.dashboardandroid.watch.engine.sanitizeWatchMusicFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copies a picker URI to private cache and extracts only the metadata needed by the watch. */
class AndroidWatchSongImportPreparer(context: Context) {
    private val appContext = context.applicationContext

    suspend fun prepare(uri: Uri): Result<WatchSongImport> = withContext(Dispatchers.IO) {
        var cacheFile: File? = null
        runCatching {
            val resolver = appContext.contentResolver
            val displayName = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty()
            val mime = resolver.getType(uri).orEmpty().lowercase()
            val hasMp3Extension = displayName.endsWith(".mp3", ignoreCase = true)
            val looksLikeMp3 = mime in MP3_MIME_TYPES ||
                ((mime.isBlank() || mime == "application/octet-stream") && hasMp3Extension)
            require(looksLikeMp3) { "Select an MP3 file." }

            val directory = File(appContext.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
            cacheFile = File(directory, "${UUID.randomUUID()}.mp3")
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected file could not be opened." }
                cacheFile!!.outputStream().use { output -> input.copyTo(output) }
            }
            val size = cacheFile!!.length()
            require(size > 0) { "The selected MP3 is empty." }

            val retriever = MediaMetadataRetriever()
            val metadata = try {
                retriever.setDataSource(cacheFile!!.absolutePath)
                Triple(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                )
            } finally {
                retriever.release()
            }
            val fallbackName = displayName.ifBlank { "Song.mp3" }
            WatchSongImport(
                privateCachePath = cacheFile!!.absolutePath,
                firmwareFileName = sanitizeWatchMusicFilename(metadata.first ?: fallbackName),
                artist = metadata.second.orEmpty().trim(),
                sizeBytes = size,
                durationMillis = metadata.third?.takeIf { it >= 0 },
            )
        }.onFailure { cacheFile?.delete() }
    }

    private companion object {
        const val CACHE_DIRECTORY = "watch-music-imports"
        val MP3_MIME_TYPES = setOf("audio/mpeg", "audio/mp3", "audio/x-mp3")
    }
}
