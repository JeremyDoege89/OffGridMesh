package dev.offgridmesh.sync

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
) {
    fun toEnvelope() = Envelope(objectType, objectId, senderId, updatedAtMs, outTtl, payload, outRelays)
}

interface GossipStore {
    /** updatedAtMs of the stored copy, or null if unknown. */
    suspend fun version(objectId: String): Long?
    suspend fun save(record: GossipRecord)
    suspend fun get(objectId: String): GossipRecord?
    suspend fun records(objectIds: List<String>): List<GossipRecord>
    /** Relayable records received since [sinceMs], newest first. */
    suspend fun relayable(sinceMs: Long, limit: Int): List<InventoryEntry>
}

/** Typed side effects of accepted objects; Room in the app, maps in tests. */
interface SyncSink {
    /** Returns true when this message is new to this device. */
    suspend fun message(m: ChatMessage, relays: Int, viaDeviceId: String?, receivedAtMs: Long): Boolean
    suspend fun presence(p: Presence, relays: Int, seenAtMs: Long)
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
    suspend fun originate(type: ObjectType, objectId: String, updatedAtMs: Long, payload: ByteArray) {
        val rec = GossipRecord(objectId, type, selfId, updatedAtMs, ttlHops(), 0, payload, clock())
        store.save(rec)
        flood(rec, exceptEndpoint = null)
    }

    /** Pushes an already-stored local object (e.g. written by a repository while the mesh was off). */
    suspend fun push(objectId: String) {
        store.get(objectId)?.let { flood(it, exceptEndpoint = null) }
    }

    suspend fun originatePresence(displayName: String, batteryPct: Int?) {
        val now = clock()
        val p = Presence(selfId, displayName, now, batteryPct)
        originate(ObjectType.PRESENCE, selfId, now, WireCodec.encodePayload(p))
    }

    private suspend fun accept(env: Envelope, endpointId: String) {
        if (env.senderId == selfId) return // our own object echoing back
        val have = store.version(env.objectId)
        if (have != null && have >= env.updatedAtMs) return
        val now = clock()
        val rec = GossipRecord(
            objectId = env.objectId,
            objectType = env.objectType,
            senderId = env.senderId,
            updatedAtMs = env.updatedAtMs,
            outTtl = (env.ttlHops - 1).coerceAtLeast(0),
            outRelays = env.relays + 1,
            payload = env.payload,
            receivedAtMs = now,
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
                sink.presence(p, env.relays, seenAt)
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
            if (endpointId == exceptEndpoint || deviceId == rec.senderId) continue
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
