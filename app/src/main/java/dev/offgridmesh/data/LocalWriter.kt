package dev.offgridmesh.data

import androidx.room.withTransaction
import dev.offgridmesh.sync.GossipRecord
import dev.offgridmesh.sync.ObjectType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/**
 * The one way local edits enter the mesh: the typed row and its gossip record are written in a single
 * transaction (so the relay queue never misses an edit), then the running engine is nudged to push it.
 */
class LocalWriter(
    private val db: MeshDatabase,
    private val identity: IdentityStore,
    private val settings: SettingsStore,
    private val outbox: MutableSharedFlow<String>,
    val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun me() = identity.current()

    /** A version that supersedes [previous] even if that carries a (clamped) future timestamp. */
    fun nextVersion(previous: Long?) = maxOf(clock(), (previous ?: 0) + 1)

    suspend fun publish(
        type: ObjectType,
        objectId: String,
        version: Long,
        payload: ByteArray,
        deleted: Boolean = false,
        applyLocal: suspend () -> Unit,
    ) {
        val ttl = settings.ttlHops.first()
        val selfId = me().deviceId
        db.withTransaction {
            applyLocal()
            db.gossip().save(GossipRecord(objectId, type, selfId, version, ttl, 0, payload, clock(), deleted))
        }
        outbox.tryEmit(objectId)
    }
}
