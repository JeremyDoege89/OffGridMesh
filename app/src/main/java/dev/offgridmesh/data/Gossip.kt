package dev.offgridmesh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.offgridmesh.sync.GossipRecord
import dev.offgridmesh.sync.GossipStore
import dev.offgridmesh.sync.InventoryEntry

/** Future-dated edits already clamped once (§5.6 clock skew). Pruned with the hop-limited records. */
@Entity(tableName = "clamped_edit", primaryKeys = ["objectId", "senderId", "originalVersion"])
data class ClampedEdit(val objectId: String, val senderId: String, val originalVersion: Long, val atMs: Long)

@Dao
interface GossipDao {
    @Query("SELECT EXISTS(SELECT 1 FROM clamped_edit WHERE objectId = :objectId AND senderId = :senderId AND originalVersion = :v)")
    suspend fun wasClamped(objectId: String, senderId: String, v: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun rememberClamped(e: ClampedEdit)

    @Query("DELETE FROM clamped_edit WHERE atMs < :beforeMs")
    suspend fun pruneClamped(beforeMs: Long): Int

    @Query("SELECT updatedAtMs FROM gossip WHERE objectId = :objectId")
    suspend fun version(objectId: String): Long?

    @Query("SELECT * FROM gossip WHERE objectId = :objectId")
    suspend fun get(objectId: String): GossipRecord?

    @Query("SELECT * FROM gossip WHERE objectId IN (:objectIds)")
    suspend fun records(objectIds: List<String>): List<GossipRecord>

    @Query(
        "SELECT objectId, updatedAtMs FROM gossip WHERE outTtl > 0 AND (receivedAtMs >= :sinceMs OR " +
            "objectType IN ('PIN', 'CHECKLIST', 'CHECKLIST_ITEM', 'STATUS')) ORDER BY receivedAtMs DESC LIMIT :limit",
    )
    suspend fun relayable(sinceMs: Long, limit: Int): List<InventoryEntry>

    @Upsert
    suspend fun save(record: GossipRecord)

    /**
     * Seen-ID cache expiry (§5.6): hop-limited objects after [hopLimitedBeforeMs]; shared state only once it
     * is a tombstone older than [tombstoneBeforeMs] (live pins etc. are kept as long as they exist).
     */
    @Query(
        "DELETE FROM gossip WHERE (objectType NOT IN ('PIN', 'CHECKLIST', 'CHECKLIST_ITEM', 'STATUS') " +
            "AND receivedAtMs < :hopLimitedBeforeMs) OR (deleted AND updatedAtMs < :tombstoneBeforeMs)",
    )
    suspend fun prune(hopLimitedBeforeMs: Long, tombstoneBeforeMs: Long): Int
}

class RoomGossipStore(private val dao: GossipDao) : GossipStore {
    override suspend fun version(objectId: String) = dao.version(objectId)
    override suspend fun save(record: GossipRecord) = dao.save(record)
    override suspend fun get(objectId: String) = dao.get(objectId)
    override suspend fun records(objectIds: List<String>) =
        objectIds.chunked(500).flatMap { dao.records(it) } // SQLite bound-parameter limit
    override suspend fun relayable(sinceMs: Long, limit: Int) = dao.relayable(sinceMs, limit)
    override suspend fun wasClamped(objectId: String, senderId: String, originalVersion: Long) =
        dao.wasClamped(objectId, senderId, originalVersion)
    override suspend fun rememberClamped(objectId: String, senderId: String, originalVersion: Long, atMs: Long) =
        dao.rememberClamped(ClampedEdit(objectId, senderId, originalVersion, atMs))
}
