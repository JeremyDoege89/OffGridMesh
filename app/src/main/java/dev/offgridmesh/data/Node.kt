package dev.offgridmesh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The node directory (Build Guide §5.1): everyone learned from discovery or presence beacons. */
@Entity(tableName = "node")
data class Node(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    /** Local observation time of the last direct or relayed contact (epoch ms). */
    val lastSeenMs: Long,
    /** 0 = direct; >0 via relay. Display only. */
    val hopCount: Int,
    val batteryPct: Int?,
)

@Dao
interface NodeDao {
    @Query("SELECT * FROM node ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<Node>>

    @Query("SELECT * FROM node WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): Node?

    @Upsert
    suspend fun upsert(node: Node)

    /**
     * Merges one contact into the directory. Contacts can arrive out of order (relays, M2), so an older
     * observation never moves lastSeen backwards; a missing battery reading keeps the last known one.
     */
    @Transaction
    suspend fun recordContact(deviceId: String, displayName: String, seenAtMs: Long, hopCount: Int, batteryPct: Int?) {
        val old = get(deviceId)
        if (old != null && seenAtMs < old.lastSeenMs) return
        upsert(
            Node(
                deviceId = deviceId,
                displayName = displayName.ifBlank { old?.displayName ?: "" },
                lastSeenMs = seenAtMs,
                hopCount = hopCount,
                batteryPct = batteryPct ?: old?.batteryPct,
            ),
        )
    }
}
