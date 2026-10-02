package dev.offgridmesh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Shared checklist (§5.4). Deleted lists are tombstones; their items simply stop showing. */
@Entity(tableName = "checklist")
data class ChecklistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val creatorId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val editorId: String,
    val deleted: Boolean,
)

@Entity(tableName = "checklist_item", indices = [Index("checklistId")])
data class ChecklistItemEntity(
    @PrimaryKey val id: String,
    val checklistId: String,
    val text: String,
    val done: Boolean,
    val doneBy: String?,
    val doneByName: String?,
    val doneAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val editorId: String,
    val deleted: Boolean,
)

data class ChecklistRow(val id: String, val title: String, val total: Int, val done: Int, val updatedAtMs: Long)

data class ChecklistItemRow(
    val id: String,
    val text: String,
    val done: Boolean,
    val doneBy: String?,
    val doneByName: String?,
    val doneAtMs: Long?,
    val updatedAtMs: Long,
)

@Dao
interface ChecklistDao {
    @Query(
        """
        SELECT c.id, c.title,
               (SELECT COUNT(*) FROM checklist_item i WHERE i.checklistId = c.id AND NOT i.deleted) AS total,
               (SELECT COUNT(*) FROM checklist_item i WHERE i.checklistId = c.id AND NOT i.deleted AND i.done) AS done,
               c.updatedAtMs
        FROM checklist c WHERE NOT c.deleted ORDER BY c.createdAtMs DESC, c.id
        """,
    )
    fun observeLists(): Flow<List<ChecklistRow>>

    @Query("SELECT * FROM checklist WHERE id = :id")
    fun observeList(id: String): Flow<ChecklistEntity?>

    @Query(
        """
        SELECT i.id, i.text, i.done, i.doneBy, COALESCE(n.displayName, i.doneByName) AS doneByName, i.doneAtMs, i.updatedAtMs
        FROM checklist_item i LEFT JOIN node n ON n.deviceId = i.doneBy
        WHERE i.checklistId = :listId AND NOT i.deleted ORDER BY i.createdAtMs, i.id
        """,
    )
    fun observeItems(listId: String): Flow<List<ChecklistItemRow>>

    @Query("SELECT * FROM checklist WHERE id = :id")
    suspend fun getList(id: String): ChecklistEntity?

    @Query("SELECT * FROM checklist_item WHERE id = :id")
    suspend fun getItem(id: String): ChecklistItemEntity?

    @Upsert
    suspend fun upsertList(c: ChecklistEntity)

    @Upsert
    suspend fun upsertItem(i: ChecklistItemEntity)

    @Transaction
    suspend fun applyListIfNewer(c: ChecklistEntity): Boolean {
        val old = getList(c.id)
        if (old != null && !newer(c.updatedAtMs, c.editorId, old.updatedAtMs, old.editorId)) return false
        upsertList(c)
        return true
    }

    /** Last-write-wins per item: if two people tick the same item while apart, the later one stands (§5.6). */
    @Transaction
    suspend fun applyItemIfNewer(i: ChecklistItemEntity): Boolean {
        val old = getItem(i.id)
        if (old != null && !newer(i.updatedAtMs, i.editorId, old.updatedAtMs, old.editorId)) return false
        upsertItem(i)
        return true
    }

    @Query("DELETE FROM checklist WHERE deleted AND updatedAtMs < :beforeMs")
    suspend fun pruneListTombstones(beforeMs: Long): Int

    @Query("DELETE FROM checklist_item WHERE deleted AND updatedAtMs < :beforeMs")
    suspend fun pruneItemTombstones(beforeMs: Long): Int
}

/** (updatedAtMs, editorId) ordering shared by every last-write-wins table. */
fun newer(v: Long, editor: String, oldV: Long, oldEditor: String) = v > oldV || (v == oldV && editor > oldEditor)
