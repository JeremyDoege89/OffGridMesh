package dev.offgridmesh.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.offgridmesh.sync.GossipRecord
import dev.offgridmesh.sync.GossipStore
import dev.offgridmesh.sync.InventoryEntry

@Dao
interface GossipDao {
    @Query("SELECT updatedAtMs FROM gossip WHERE objectId = :objectId")
    suspend fun version(objectId: String): Long?

    @Query("SELECT * FROM gossip WHERE objectId = :objectId")
    suspend fun get(objectId: String): GossipRecord?

    @Query("SELECT * FROM gossip WHERE objectId IN (:objectIds)")
    suspend fun records(objectIds: List<String>): List<GossipRecord>

    @Query(
        "SELECT objectId, updatedAtMs FROM gossip WHERE outTtl > 0 AND receivedAtMs >= :sinceMs " +
            "ORDER BY receivedAtMs DESC LIMIT :limit",
    )
    suspend fun relayable(sinceMs: Long, limit: Int): List<InventoryEntry>

    @Upsert
    suspend fun save(record: GossipRecord)

    /** Seen-ID cache expiry (§5.6). */
    @Query("DELETE FROM gossip WHERE receivedAtMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}

class RoomGossipStore(private val dao: GossipDao) : GossipStore {
    override suspend fun version(objectId: String) = dao.version(objectId)
    override suspend fun save(record: GossipRecord) = dao.save(record)
    override suspend fun get(objectId: String) = dao.get(objectId)
    override suspend fun records(objectIds: List<String>) =
        objectIds.chunked(500).flatMap { dao.records(it) } // SQLite bound-parameter limit
    override suspend fun relayable(sinceMs: Long, limit: Int) = dao.relayable(sinceMs, limit)
}
