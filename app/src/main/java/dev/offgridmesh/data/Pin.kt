package dev.offgridmesh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.offgridmesh.sync.PinType
import kotlinx.coroutines.flow.Flow

/** A map pin (§5.3). Pins are data, never baked into tiles. Deletes are tombstones kept 30 days. */
@Entity(tableName = "pin")
data class PinEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lng: Double,
    val label: String,
    val type: PinType,
    val creatorId: String,
    val creatorName: String,
    val createdAtMs: Long,
    /** Last-write-wins input (§5.6). */
    val updatedAtMs: Long,
    val editorId: String,
    val deleted: Boolean,
)

data class PinRow(
    val id: String,
    val lat: Double,
    val lng: Double,
    val label: String,
    val type: PinType,
    val creatorId: String,
    val creatorName: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Dao
interface PinDao {
    @Query(
        """
        SELECT p.id, p.lat, p.lng, p.label, p.type, p.creatorId,
               COALESCE(n.displayName, p.creatorName) AS creatorName, p.createdAtMs, p.updatedAtMs
        FROM pin p LEFT JOIN node n ON n.deviceId = p.creatorId
        WHERE NOT p.deleted ORDER BY p.createdAtMs
        """,
    )
    fun observeLive(): Flow<List<PinRow>>

    @Query("SELECT * FROM pin WHERE id = :id")
    suspend fun get(id: String): PinEntity?

    @Upsert
    suspend fun upsert(p: PinEntity)

    /** Applies a remote version only if it is newer (ties broken by editor id so every phone agrees). */
    @Transaction
    suspend fun applyIfNewer(p: PinEntity): Boolean {
        val old = get(p.id)
        if (old != null && (old.updatedAtMs > p.updatedAtMs ||
                (old.updatedAtMs == p.updatedAtMs && old.editorId >= p.editorId))
        ) return false
        upsert(p)
        return true
    }

    @Query("DELETE FROM pin WHERE deleted AND updatedAtMs < :beforeMs")
    suspend fun pruneTombstones(beforeMs: Long): Int
}
