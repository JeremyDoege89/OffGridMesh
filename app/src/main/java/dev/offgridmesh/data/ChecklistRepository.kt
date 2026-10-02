package dev.offgridmesh.data

import dev.offgridmesh.sync.ChecklistItemPayload
import dev.offgridmesh.sync.ChecklistPayload
import dev.offgridmesh.sync.ObjectType
import dev.offgridmesh.sync.WireCodec
import kotlinx.coroutines.flow.Flow
import java.util.UUID

fun ChecklistPayload.toEntity() = ChecklistEntity(id, title, creatorId, createdAtMs, updatedAtMs, editorId, deleted)
fun ChecklistEntity.toPayload() = ChecklistPayload(id, title, creatorId, createdAtMs, updatedAtMs, editorId, deleted)
fun ChecklistItemPayload.toEntity() =
    ChecklistItemEntity(id, checklistId, text, done, doneBy, doneByName, doneAtMs, createdAtMs, updatedAtMs, editorId, deleted)
fun ChecklistItemEntity.toPayload() =
    ChecklistItemPayload(id, checklistId, text, done, doneBy, doneByName, doneAtMs, createdAtMs, updatedAtMs, editorId, deleted)

/** Checklists (§7.5): local-first; nothing ever waits for a peer. Reorder is v2. */
class ChecklistRepository(private val db: MeshDatabase, private val w: LocalWriter) {
    private val dao get() = db.checklists()

    fun observeLists(): Flow<List<ChecklistRow>> = dao.observeLists()
    fun observeList(id: String) = dao.observeList(id)
    fun observeItems(listId: String): Flow<List<ChecklistItemRow>> = dao.observeItems(listId)

    suspend fun createList(title: String): String {
        val me = w.me()
        val now = w.clock()
        val c = ChecklistPayload(UUID.randomUUID().toString(), title.trim().take(ChecklistPayload.MAX_TITLE), me.deviceId, now, now, me.deviceId)
        saveList(c)
        return c.id
    }

    suspend fun renameList(id: String, title: String) = editList(id) { it.copy(title = title.trim().take(ChecklistPayload.MAX_TITLE)) }

    suspend fun deleteList(id: String) = editList(id) { it.copy(deleted = true) }

    suspend fun addItem(listId: String, text: String) {
        val body = text.trim().take(ChecklistItemPayload.MAX_TEXT)
        if (body.isEmpty()) return
        val me = w.me()
        val now = w.clock()
        saveItem(ChecklistItemPayload(UUID.randomUUID().toString(), listId, body, false, createdAtMs = now, updatedAtMs = now, editorId = me.deviceId))
    }

    suspend fun setDone(itemId: String, done: Boolean) {
        val me = w.me()
        editItem(itemId) {
            if (done) it.copy(done = true, doneBy = me.deviceId, doneByName = me.displayName, doneAtMs = w.clock())
            else it.copy(done = false, doneBy = null, doneByName = null, doneAtMs = null)
        }
    }

    suspend fun editItemText(itemId: String, text: String) =
        editItem(itemId) { it.copy(text = text.trim().take(ChecklistItemPayload.MAX_TEXT)) }

    suspend fun deleteItem(itemId: String) = editItem(itemId) { it.copy(deleted = true) }

    private suspend fun editList(id: String, change: (ChecklistPayload) -> ChecklistPayload) {
        val old = dao.getList(id)?.takeIf { !it.deleted } ?: return
        val me = w.me()
        saveList(change(old.toPayload()).copy(updatedAtMs = w.nextVersion(old.updatedAtMs), editorId = me.deviceId))
    }

    private suspend fun editItem(id: String, change: (ChecklistItemPayload) -> ChecklistItemPayload) {
        val old = dao.getItem(id)?.takeIf { !it.deleted } ?: return
        val me = w.me()
        saveItem(change(old.toPayload()).copy(updatedAtMs = w.nextVersion(old.updatedAtMs), editorId = me.deviceId))
    }

    private suspend fun saveList(c: ChecklistPayload) =
        w.publish(ObjectType.CHECKLIST, c.id, c.updatedAtMs, WireCodec.encodePayload(c), c.deleted) { dao.upsertList(c.toEntity()) }

    private suspend fun saveItem(i: ChecklistItemPayload) =
        w.publish(ObjectType.CHECKLIST_ITEM, i.id, i.updatedAtMs, WireCodec.encodePayload(i), i.deleted) { dao.upsertItem(i.toEntity()) }
}
