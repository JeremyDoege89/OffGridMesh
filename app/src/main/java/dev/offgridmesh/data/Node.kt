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
    /** Last shared position, only if the member shares location (§6). Never required. */
    val lat: Double? = null,
    val lng: Double? = null,
    val locationAtMs: Long? = null,
    /** Their clock minus ours, from the last direct beacon. Shown when far off (§5.6, §14 clock-skew). */
    val clockSkewMs: Long? = null,
)

@Dao
interface NodeDao {
    @Query("SELECT * FROM node ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<Node>>

    @Query("SELECT * FROM node WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): Node?

    @Query("SELECT deviceId FROM node WHERE lastSeenMs >= :sinceMs AND deviceId != :selfId")
    suspend fun recentIds(sinceMs: Long, selfId: String): List<String>

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
                lat = old?.lat,
                lng = old?.lng,
                locationAtMs = old?.locationAtMs,
                clockSkewMs = old?.clockSkewMs,
            ),
        )
    }

    @Query("UPDATE node SET clockSkewMs = :skewMs WHERE deviceId = :deviceId")
    suspend fun setClockSkew(deviceId: String, skewMs: Long)

    /** A presence beacon's position; null clears it (the member stopped sharing). */
    @Query("UPDATE node SET lat = :lat, lng = :lng, locationAtMs = :atMs WHERE deviceId = :deviceId AND (locationAtMs IS NULL OR locationAtMs <= :atMs)")
    suspend fun setLocation(deviceId: String, lat: Double?, lng: Double?, atMs: Long)
}
