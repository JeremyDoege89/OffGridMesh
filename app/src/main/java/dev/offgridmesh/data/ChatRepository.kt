package dev.offgridmesh.data

import androidx.room.withTransaction
import dev.offgridmesh.mesh.MeshConfig
import dev.offgridmesh.sync.ChatMessage
import dev.offgridmesh.sync.GossipRecord
import dev.offgridmesh.sync.ObjectType
import dev.offgridmesh.sync.PinPayload
import dev.offgridmesh.sync.Presence
import dev.offgridmesh.sync.SyncSink
import dev.offgridmesh.sync.WireCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Local-first chat writes (§7.3): a message is stored together with its gossip record in one transaction,
 * so it is in the relay queue the moment it exists — whether or not the mesh is running.
 */
class ChatRepository(
    private val db: MeshDatabase,
    private val identity: IdentityStore,
    private val settings: SettingsStore,
    private val outbox: MutableSharedFlow<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(channelId: String = ChatMessage.MAIN_CHANNEL): Flow<List<ChatRow>> =
        db.messages().observeChannel(channelId)

    /** Returns the new message id, or null when there was nothing to send. Over-long text is cut at the cap. */
    suspend fun send(text: String): String? {
        val body = text.trim().take(ChatMessage.MAX_CHARS)
        if (body.isEmpty()) return null
        val me = identity.current()
        val ttl = settings.ttlHops.first()
        val now = clock()
        val m = ChatMessage(UUID.randomUUID().toString(), me.deviceId, me.displayName, now, body)
        db.withTransaction {
            db.messages().insert(
                MessageEntity(
                    id = m.id, senderId = m.senderId, senderName = m.senderName, timestampMs = now, text = body,
                    ttlHops = ttl, state = MessageState.LOCAL_ONLY, channelId = m.channelId, receivedAtMs = now,
                ),
            )
            snapshotRecipients(db, m.id, me.deviceId, now)
            db.gossip().save(
                GossipRecord(m.id, ObjectType.MESSAGE, me.deviceId, now, ttl, 0, WireCodec.encodePayload(m), now),
            )
        }
        outbox.tryEmit(m.id)
        return m.id
    }
}

/** "Delivered to all current members" = everyone seen recently when the message first leaves (§9). */
private suspend fun snapshotRecipients(db: MeshDatabase, messageId: String, selfId: String, now: Long) {
    if (db.messages().expectedCount(messageId) > 0) return
    val members = db.nodes().recentIds(now - MeshConfig.OFFLINE_AFTER_MS, selfId)
    db.messages().insertRecipients(members.map { MessageRecipient(messageId, it, expected = true, ackedAtMs = null) })
}

/** SyncEngine's typed effects, written to Room. */
class RoomSyncSink(
    private val db: MeshDatabase,
    private val selfId: String,
    private val onNewMessage: (ChatMessage) -> Unit = {},
) : SyncSink {
    override suspend fun message(m: ChatMessage, relays: Int, viaDeviceId: String?, receivedAtMs: Long): Boolean {
        val inserted = db.messages().insert(
            MessageEntity(
                id = m.id, senderId = m.senderId, senderName = m.senderName, timestampMs = m.timestampMs,
                text = m.text, ttlHops = 0, state = MessageState.RECEIVED, channelId = m.channelId,
                relays = relays, viaDeviceId = viaDeviceId, receivedAtMs = receivedAtMs,
            ),
        ) != -1L
        db.nodes().recordContact(m.senderId, m.senderName, m.timestampMs.coerceAtMost(receivedAtMs), relays, null)
        if (inserted) onNewMessage(m)
        return inserted
    }

    override suspend fun presence(p: Presence, relays: Int, seenAtMs: Long, clockSkewMs: Long?) {
        db.nodes().recordContact(p.deviceId, p.displayName, seenAtMs, relays, p.batteryPct)
        clockSkewMs?.let { db.nodes().setClockSkew(p.deviceId, it) }
        val lat = p.lat?.takeIf { it in -90.0..90.0 }
        val lng = p.lng?.takeIf { it in -180.0..180.0 }
        db.nodes().setLocation(p.deviceId, lat?.takeIf { lng != null }, lng?.takeIf { lat != null }, p.timestampMs)
    }

    override suspend fun pin(p: PinPayload): Boolean = db.pins().applyIfNewer(p.toEntity())

    override suspend fun ack(messageId: String, ackerId: String, atMs: Long) {
        db.messages().recordAck(messageId, ackerId, atMs)
    }

    override suspend fun relayed(messageId: String, atMs: Long) {
        db.withTransaction {
            // Composed with nobody around: the recipient set is whoever is around when it first leaves.
            if (db.messages().markRelayed(messageId, atMs) > 0) snapshotRecipients(db, messageId, selfId, atMs)
        }
    }
}
