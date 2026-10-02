package dev.offgridmesh.data

import androidx.room.withTransaction
import dev.offgridmesh.sync.GossipRecord
import dev.offgridmesh.sync.ObjectType
import dev.offgridmesh.sync.PinPayload
import dev.offgridmesh.sync.PinType
import dev.offgridmesh.sync.WireCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.util.UUID

fun PinPayload.toEntity() = PinEntity(
    id, lat, lng, label, type, creatorId, creatorName, createdAtMs, updatedAtMs, editorId, deleted,
)

fun PinEntity.toPayload() = PinPayload(
    id, lat, lng, label, type, creatorId, creatorName, createdAtMs, updatedAtMs, editorId, deleted,
)

/** Pin CRUD (§7.4). Local-first: each write is a full new version in Room plus a gossip record. */
class PinRepository(
    private val db: MeshDatabase,
    private val identity: IdentityStore,
    private val settings: SettingsStore,
    private val outbox: MutableSharedFlow<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<List<PinRow>> = db.pins().observeLive()

    suspend fun create(lat: Double, lng: Double, label: String, type: PinType): String {
        val me = identity.current()
        val now = clock()
        val p = PinPayload(
            id = UUID.randomUUID().toString(), lat = lat, lng = lng, label = clean(label), type = type,
            creatorId = me.deviceId, creatorName = me.displayName, createdAtMs = now, updatedAtMs = now,
            editorId = me.deviceId,
        )
        write(p)
        return p.id
    }

    suspend fun edit(id: String, label: String, type: PinType) {
        val old = db.pins().get(id)?.takeIf { !it.deleted } ?: return
        write(next(old).copy(label = clean(label), type = type))
    }

    suspend fun delete(id: String) {
        val old = db.pins().get(id)?.takeIf { !it.deleted } ?: return
        write(next(old).copy(deleted = true))
    }

    /**
     * A local edit always supersedes what this phone shows, even if that version carries a (clamped) future
     * timestamp from a skewed clock.
     */
    private suspend fun next(old: PinEntity): PinPayload {
        val me = identity.current()
        return old.toPayload().copy(updatedAtMs = maxOf(clock(), old.updatedAtMs + 1), editorId = me.deviceId)
    }

    private suspend fun write(p: PinPayload) {
        val ttl = settings.ttlHops.first()
        val me = identity.current()
        db.withTransaction {
            db.pins().upsert(p.toEntity())
            db.gossip().save(
                GossipRecord(
                    p.id, ObjectType.PIN, me.deviceId, p.updatedAtMs, ttl, 0, WireCodec.encodePayload(p), clock(),
                    deleted = p.deleted,
                ),
            )
        }
        outbox.tryEmit(p.id)
    }

    private fun clean(label: String) = label.trim().take(PinPayload.MAX_LABEL)
}
