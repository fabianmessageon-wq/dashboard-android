package dev.jaredhq.dashboardandroid.data.cache.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dev.jaredhq.dashboardandroid.data.api.dto.TodayPayloadDto
import dev.jaredhq.dashboardandroid.data.api.dto.toDomain
import dev.jaredhq.dashboardandroid.data.api.dto.toDto
import dev.jaredhq.dashboardandroid.data.cache.TodayCache
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import kotlinx.serialization.json.Json

/**
 * Room-backed [TodayCache]. The Today payload is a nested object, so rather than
 * model every field as a column we store ONE row holding the payload as a JSON
 * blob (serialized via the same DTO that decodes the wire). This keeps the
 * schema stable across contract changes — only the JSON content evolves.
 *
 * Single-row table keyed by a constant id; the latest fetch overwrites it.
 */

@Entity(tableName = "today_snapshot")
data class TodaySnapshotEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val json: String,
    val cachedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface TodayDao {
    @Query("SELECT * FROM today_snapshot WHERE id = :id LIMIT 1")
    suspend fun get(id: Int = TodaySnapshotEntity.SINGLETON_ID): TodaySnapshotEntity?

    @Upsert
    suspend fun upsert(entity: TodaySnapshotEntity)

    @Query("DELETE FROM today_snapshot")
    suspend fun clear()
}

@Database(entities = [TodaySnapshotEntity::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun todayDao(): TodayDao
}

/**
 * Bridges the Room DAO to the domain-typed [TodayCache] contract. Serialization
 * lives here (not in the DAO) so the persistence layer stays decoupled from the
 * wire format details.
 */
class RoomTodayCache(
    private val dao: TodayDao,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : TodayCache {

    override suspend fun load(): TodayPayload? {
        val row = dao.get() ?: return null
        return runCatching {
            json.decodeFromString(TodayPayloadDto.serializer(), row.json).toDomain()
        }.getOrNull()
    }

    override suspend fun save(payload: TodayPayload) {
        val encoded = json.encodeToString(TodayPayloadDto.serializer(), payload.toDto())
        dao.upsert(TodaySnapshotEntity(json = encoded, cachedAt = System.currentTimeMillis()))
    }

    override suspend fun clear() = dao.clear()
}
