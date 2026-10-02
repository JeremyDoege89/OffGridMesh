package dev.offgridmesh.sync

import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.offgridmesh.mesh.MeshConfig
import dev.offgridmesh.mesh.MeshListener

/**
 * Every relayable object as it travels: the store-and-forward queue (§5.6) is simply "records with
 * outTtl > 0 that a connected peer doesn't have yet", so it lives in Room and survives process death.
 * Typed tables (message, node, …) are projections the UI reads; this table is what gets gossiped.
 */
@Entity(tableName = "gossip", indices = [Index("receivedAtMs")])
class GossipRecord(
    @PrimaryKey val objectId: String,
    val objectType: ObjectType,
    val senderId: String,
    val updatedAtMs: Long,
    /** ttlHops to put on the envelope when this device sends it on; 0 = don't send on. */
    val outTtl: Int,
    /** relays to put on the envelope when this device sends it on. */
    val outRelays: Int,
    val payload: ByteArray,
    val receivedAtMs: Long,
    /** Tombstone: kept and relayed for 30 days so the delete reaches phones that were away (§5). */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) {
    fun toEnvelope() = Envelope(objectType, objectId, senderId, updatedAtMs, outTtl, payload, outRelays)
}

interface GossipStore {
    /** updatedAtMs of the stored copy, or null if unknown. */
    suspend fun version(objectId: String): Long?
    suspend fun save(record: GossipRecord)
    suspend fun get(objectId: String): GossipRecord?
    suspend fun records(objectIds: List<String>): List<GossipRecord>
    /** True if this exact future-dated edit was already clamped once (so a re-offer can't win again). */
    suspend fun wasClamped(objectId: String, senderId: String, originalVersion: Long): Boolean
    suspend fun rememberClamped(objectId: String, senderId: String, originalVersion: Long, atMs: Long)

    /** Relayable records: hop-limited ones received since [sinceMs], plus all replicated state; newest first. */
    suspend fun relayable(sinceMs: Long, limit: Int): List<InventoryEntry>
}

/** Typed side effects of accepted objects; Room in the app, maps in tests. */
interface SyncSink {
    /** Returns true when this message is new to this device. */
    suspend fun message(m: ChatMessage, relays: Int, viaDeviceId: String?, receivedAtMs: Long): Boolean
    /** [clockSkewMs]: author clock minus ours, measured from a direct beacon; null when relayed. */
    suspend fun presence(p: Presence, relays: Int, seenAtMs: Long, clockSkewMs: Long?)
    /** Returns true when this version was applied (it was newer than what the phone had). */
    suspend fun pin(p: PinPayload): Boolean

    /** A receipt for one of our own messages. */
    suspend fun ack(messageId: String, ackerId: String, atMs: Long)
    /** One of our own messages was handed to a connected peer for the first time (Queued → Relayed). */
    suspend fun relayed(messageId: String, atMs: Long)
}

/** What SyncEngine needs from the connection layer (MeshCore). */
interface PeerLink {
    /** endpointId → deviceId of every connected endpoint. */
    fun connectedPeers(): Map<String, String?>
    fun send(endpointId: String, bytes: ByteArray)
}

/**
 * Epidemic gossip (§5.6, Appendix B), transport-agnostic.
 *
 * - On connect: send an inventory of relayable ids; the peer requests what it lacks or has older.
 * - On receive: dedup by id (and updatedAtMs for mutable objects), persist, apply, then flood to every other
 *   connected peer if TTL remains. A relay sends ttl−1, so TTL 3 reaches at most three radio hops.
 * - Local objects go through [originate]; anything not yet handed on is picked up by the next inventory.
 *
 * Unknown object types from newer builds are stored and relayed untouched.
 */
class SyncEngine(
    private val selfId: String,
    private val store: GossipStore,
    private val sink: SyncSink,
    private val link: PeerLink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlHops: () -> Int = { MeshConfig.DEFAULT_TTL_HOPS },
) : MeshListener {
    override suspend fun onConnected(endpointId: String) {
        val entries = store.relayable(clock() - MeshConfig.GOSSIP_RETENTION_MS, MeshConfig.INVENTORY_MAX_ENTRIES)
        entries.chunked(MeshConfig.INVENTORY_PAGE).forEach { page ->
            sendControl(endpointId, ObjectType.INVENTORY, WireCodec.encodePayload(Inventory(page)))
        }
    }

    override suspend fun onReceived(endpointId: String, bytes: ByteArray) {
        val env = WireCodec.decode(bytes) ?: return
        when (env.objectType) {
            ObjectType.INVENTORY -> {
                val inv = WireCodec.decodePayload<Inventory>(env.payload) ?: return
                val lacking = inv.entries.filter { e ->
                    val have = store.version(e.objectId)
                    have == null || have < e.updatedAtMs
                }.map { it.objectId }
                lacking.chunked(MeshConfig.INVENTORY_PAGE).forEach { ids ->
                    sendControl(endpointId, ObjectType.REQUEST, WireCodec.encodePayload(Request(ids)))
                }
            }

            ObjectType.REQUEST -> {
                val req = WireCodec.decodePayload<Request>(env.payload) ?: return
                store.records(req.objectIds)
                    .filter { it.outTtl > 0 }
                    .sortedBy { priority(it.objectType) }
                    .forEach { sendRecord(endpointId, it) }
            }

            else -> accept(env, endpointId)
        }
    }

    /** Stores a locally created object and hands it to every connected peer. */
    suspend fun originate(
        type: ObjectType,
        objectId: String,
        updatedAtMs: Long,
        payload: ByteArray,
        deleted: Boolean = false,
    ) {
        val rec = GossipRecord(objectId, type, selfId, updatedAtMs, ttlHops(), 0, payload, clock(), deleted)
        store.save(rec)
        flood(rec, exceptEndpoint = null)
    }

    /** Pushes an already-stored local object (e.g. written by a repository while the mesh was off). */
    suspend fun push(objectId: String) {
        store.get(objectId)?.let { flood(it, exceptEndpoint = null) }
    }

    suspend fun originatePresence(displayName: String, batteryPct: Int?, location: android.location.Location? = null) {
        val now = clock()
        val p = Presence(selfId, displayName, now, batteryPct, location?.latitude, location?.longitude)
        originate(ObjectType.PRESENCE, selfId, now, WireCodec.encodePayload(p))
    }

    private suspend fun accept(env: Envelope, endpointId: String) {
        val now = clock()
        // §5.6: a phone with a wrong clock must not win every conflict. Clamp versions to ≤ 24 h ahead.
        val version = env.updatedAtMs.coerceAtMost(now + MeshConfig.MAX_FUTURE_SKEW_MS)
        if (version != env.updatedAtMs) {
            // Without this memory the skewed phone's re-offers would be re-clamped to a later "now + 24 h"
            // every time and keep beating newer real edits.
            if (store.wasClamped(env.objectId, env.senderId, env.updatedAtMs)) return
            store.rememberClamped(env.objectId, env.senderId, env.updatedAtMs, now)
            Log.w(TAG, "clamped future timestamp on ${env.objectType} ${env.objectId} from ${env.senderId}")
        }
        val have = store.version(env.objectId)
        if (have != null && have >= version) return
        val replicated = env.objectType.replicated
        // Our own hop-limited objects never need taking back; our own shared state can (another phone
        // may hold a newer edit of it only if it was edited elsewhere, so the version check covers that).
        if (env.senderId == selfId && !replicated) return

        var deleted = false
        when (env.objectType) {
            ObjectType.PIN -> {
                val p = WireCodec.decodePayload<PinPayload>(env.payload) ?: return
                if (p.id != env.objectId) return
                deleted = p.deleted
                sink.pin(p.copy(updatedAtMs = version, label = p.label.take(PinPayload.MAX_LABEL)))
            }
            else -> Unit
        }

        val rec = GossipRecord(
            objectId = env.objectId,
            objectType = env.objectType,
            senderId = env.senderId,
            updatedAtMs = version,
            outTtl = if (replicated) env.ttlHops.coerceAtLeast(1) else (env.ttlHops - 1).coerceAtLeast(0),
            outRelays = env.relays + 1,
            payload = env.payload,
            receivedAtMs = now,
            deleted = deleted,
        )
        store.save(rec)

        val fromDevice = link.connectedPeers()[endpointId]
        when (env.objectType) {
            ObjectType.MESSAGE -> {
                val m = WireCodec.decodePayload<ChatMessage>(env.payload) ?: return
                if (m.id != env.objectId || m.senderId != env.senderId) return
                // §5.6: a wrong clock must not put a message a day ahead of everything else.
                val clamped = m.copy(
                    timestampMs = m.timestampMs.coerceAtMost(now + MeshConfig.MAX_FUTURE_SKEW_MS),
                    text = m.text.take(ChatMessage.MAX_CHARS),
                )
                val isNew = sink.message(clamped, env.relays, fromDevice.takeIf { env.relays > 0 }, now)
                if (isNew) {
                    val ack = Ack(m.id, m.senderId, selfId, now)
                    originate(ObjectType.ACK, Ack.objectId(m.id, selfId), now, WireCodec.encodePayload(ack))
                }
            }

            ObjectType.PRESENCE -> {
                val p = WireCodec.decodePresence(env.payload) ?: return
                if (p.deviceId != env.senderId) return
                // Direct: we saw it now. Relayed: it was true when the author sent it (never in our future).
                val seenAt = if (env.relays == 0) now else p.timestampMs.coerceAtMost(now)
                // Only a direct beacon measures a peer's clock; relayed ones are delayed by the path.
                val skew = if (env.relays == 0) p.timestampMs - now else null
                sink.presence(p, env.relays, seenAt, skew)
            }

            ObjectType.ACK -> {
                val a = WireCodec.decodePayload<Ack>(env.payload) ?: return
                if (a.authorId == selfId && a.ackerId == env.senderId) sink.ack(a.messageId, a.ackerId, now)
            }

            else -> Unit
        }
        if (rec.outTtl > 0) flood(rec, exceptEndpoint = endpointId)
    }

    private suspend fun flood(rec: GossipRecord, exceptEndpoint: String?) {
        if (rec.outTtl <= 0) return
        for ((endpointId, deviceId) in link.connectedPeers()) {
            if (endpointId == exceptEndpoint) continue
            if (deviceId == rec.senderId && !rec.objectType.replicated) continue
            sendRecord(endpointId, rec)
        }
    }

    private suspend fun sendRecord(endpointId: String, rec: GossipRecord) {
        link.send(endpointId, WireCodec.encode(rec.toEnvelope()))
        if (rec.objectType == ObjectType.MESSAGE && rec.senderId == selfId) sink.relayed(rec.objectId, clock())
    }

    private fun sendControl(endpointId: String, type: ObjectType, payload: ByteArray) {
        link.send(endpointId, WireCodec.encode(Envelope(type, "", selfId, clock(), 0, payload)))
    }

    private companion object {
        const val TAG = "SyncEngine"

        /** Relay order (§5.6): NEED_HELP/status first, then pins, messages, receipts, checklists, presence. */
        fun priority(t: ObjectType) = when (t) {
            ObjectType.STATUS -> 0
            ObjectType.PIN -> 1
            ObjectType.MESSAGE -> 2
            ObjectType.ACK -> 3
            ObjectType.CHECKLIST, ObjectType.CHECKLIST_ITEM -> 4
            ObjectType.PRESENCE -> 5
            ObjectType.INVENTORY, ObjectType.REQUEST -> 6
        }
    }
}
