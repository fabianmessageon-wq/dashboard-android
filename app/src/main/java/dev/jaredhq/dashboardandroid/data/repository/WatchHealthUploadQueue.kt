package dev.jaredhq.dashboardandroid.data.repository

import android.util.Log
import dev.jaredhq.dashboardandroid.data.api.dto.WatchHealthUploadDto
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A small durable spool for watch-health uploads whose POST failed.
 *
 * Why it's needed: the BLE sync clears the watch's own buffer once it hands data to us, and
 * [dev.jaredhq.dashboardandroid.watch.engine.UploadingWatchHealthListener.flush] clears our
 * in-memory buffers *before* the upload — so a failed POST would otherwise lose a whole sync with
 * no way to recover it. On failure the repository spools the wire DTO here as a JSON file; the next
 * successful upload (or worker run) drains the backlog, deleting each file only once the server
 * accepts it.
 *
 * File-backed (not Room) so it's trivially testable with a temp dir and needs no schema/migration.
 * [dir] is created on demand. Bounded to [maxEntries] files so a long offline stretch can't grow
 * unbounded; the oldest are dropped first (mirrors the watch's own volatile-buffer semantics —
 * losing the oldest unsent sync is strictly better than losing the newest, and far better than
 * losing every sync as today).
 */
class WatchHealthUploadQueue(
    private val dir: File,
    private val maxEntries: Int = 50,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val lock = Any()
    private var counter = 0

    /** A spooled upload: the backing [file] and its decoded [dto]. */
    data class Pending(val file: File, val dto: WatchHealthUploadDto)

    /** Spool a failed upload. Bounded — prunes the oldest beyond [maxEntries]. Never throws. */
    fun enqueue(dto: WatchHealthUploadDto) {
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                // millis + monotonic counter keeps names unique and lexically sortable by arrival.
                val name = "%013d-%04d.json".format(System.currentTimeMillis(), counter++)
                File(dir, name).writeText(
                    json.encodeToString(WatchHealthUploadDto.serializer(), dto),
                )
                prune()
            }.onFailure { Log.w(TAG, "enqueue failed: ${it.message}") }
        }
    }

    /** Pending spooled uploads, oldest first (so retries preserve arrival order). Files that fail
     *  to decode are corrupt and are deleted rather than blocking the queue forever. */
    fun pending(): List<Pending> = synchronized(lock) {
        val files = spoolFiles() ?: return emptyList()
        files.mapNotNull { f ->
            val dto = runCatching {
                json.decodeFromString(WatchHealthUploadDto.serializer(), f.readText())
            }.getOrNull()
            if (dto == null) {
                f.delete()
                null
            } else {
                Pending(f, dto)
            }
        }
    }

    /** Delete a spooled entry after the server has accepted it. */
    fun remove(pending: Pending) {
        synchronized(lock) { runCatching { pending.file.delete() } }
    }

    /** Number of spooled uploads still awaiting a successful POST. */
    fun size(): Int = synchronized(lock) { spoolFiles()?.size ?: 0 }

    private fun spoolFiles(): List<File>? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }

    private fun prune() {
        val files = spoolFiles() ?: return
        val excess = files.size - maxEntries
        for (i in 0 until excess) files[i].delete()
    }

    private companion object {
        const val TAG = "WatchHealthQueue"
    }
}
