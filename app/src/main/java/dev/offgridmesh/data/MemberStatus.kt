package dev.offgridmesh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.offgridmesh.sync.MemberState
import kotlinx.coroutines.flow.Flow

/** A member's own OK / NEED_HELP (§5.5). OFFLINE is derived from Node.lastSeenMs, never stored. */
@Entity(tableName = "member_status")
data class MemberStatusEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val state: MemberState,
    val updatedAtMs: Long,
    val note: String?,
    val lat: Double?,
    val lng: Double?,
)

@Dao
interface MemberStatusDao {
    @Query("SELECT * FROM member_status")
    fun observeAll(): Flow<List<MemberStatusEntity>>

    @Query("SELECT * FROM member_status WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): MemberStatusEntity?

    @Upsert
    suspend fun upsert(s: MemberStatusEntity)

    /**
     * The whole rule is the timestamp (§5.5): a stale OK never overwrites a newer NEED_HELP, and a newer
     * clear does. Returns the previous row when applied, so the caller can tell a new help call from a repeat.
     */
    @Transaction
    suspend fun applyIfNewer(s: MemberStatusEntity): Pair<Boolean, MemberStatusEntity?> {
        val old = get(s.deviceId)
        if (old != null && old.updatedAtMs >= s.updatedAtMs) return false to old
        upsert(s)
        return true to old
    }
}
