package dev.offgridmesh.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Own messages move LOCAL_ONLY (shown "Queued") → RELAYED → DELIVERED (§9). Messages from others are
 * RECEIVED. "Sent" is deliberately not a state.
 */
enum class MessageState { LOCAL_ONLY, RELAYED, DELIVERED, RECEIVED }

/** Group chat message (§5.2), one row per id — the id is also the gossip dedup key. */
@Entity(tableName = "message")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val senderName: String,
    /** Author clock, clamped on receipt (§5.6); ordering tie-breaks on id. */
    val timestampMs: Long,
    val text: String,
    /** TTL the author set (own messages) — display only. */
    val ttlHops: Int,
    val state: MessageState,
    val channelId: String,
    /** Received only: relays before it reached us (0 = direct from the author). */
    val relays: Int = 0,
    /** Received only: the device that handed it to us when relayed. */
    val viaDeviceId: String? = null,
    val receivedAtMs: Long,
    val relayedAtMs: Long? = null,
)

/**
 * Own messages only: who must acknowledge before the message counts as delivered. `expected` rows are the
 * members known when the message first left this phone; later joiners' receipts are kept but don't count.
 */
@Entity(tableName = "message_recipient", primaryKeys = ["messageId", "deviceId"])
data class MessageRecipient(
    val messageId: String,
    val deviceId: String,
    val expected: Boolean,
    val ackedAtMs: Long?,
)

/** A chat row joined with the names the UI needs. */
data class ChatRow(
    val id: String,
    val senderId: String,
    val senderName: String,
    val timestampMs: Long,
    val text: String,
    val state: MessageState,
    val relays: Int,
    val viaName: String?,
    val receivedAtMs: Long,
    val expectedCount: Int,
    val ackedCount: Int,
)

@Dao
interface MessageDao {
    @Query(
        """
        SELECT m.id, m.senderId, COALESCE(n.displayName, m.senderName) AS senderName, m.timestampMs, m.text,
               m.state, m.relays, v.displayName AS viaName, m.receivedAtMs,
               (SELECT COUNT(*) FROM message_recipient r WHERE r.messageId = m.id AND r.expected) AS expectedCount,
               (SELECT COUNT(*) FROM message_recipient r
                 WHERE r.messageId = m.id AND r.expected AND r.ackedAtMs IS NOT NULL) AS ackedCount
        FROM message m
        LEFT JOIN node n ON n.deviceId = m.senderId
        LEFT JOIN node v ON v.deviceId = m.viaDeviceId
        WHERE m.channelId = :channelId
        ORDER BY m.timestampMs, m.id
        """,
    )
    fun observeChannel(channelId: String): Flow<List<ChatRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecipients(rows: List<MessageRecipient>)

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM message_recipient WHERE messageId = :id AND expected")
    suspend fun expectedCount(id: String): Int

    @Query("UPDATE message SET state = 'RELAYED', relayedAtMs = :atMs WHERE id = :id AND state = 'LOCAL_ONLY'")
    suspend fun markRelayed(id: String, atMs: Long): Int

    @Query(
        "UPDATE message_recipient SET ackedAtMs = :atMs WHERE messageId = :messageId AND deviceId = :deviceId " +
            "AND ackedAtMs IS NULL",
    )
    suspend fun ackExisting(messageId: String, deviceId: String, atMs: Long): Int

    /** Delivered once every expected member has acknowledged; never before the message was relayed. */
    @Query(
        """
        UPDATE message SET state = 'DELIVERED'
        WHERE id = :id AND state IN ('LOCAL_ONLY', 'RELAYED')
          AND EXISTS (SELECT 1 FROM message_recipient r WHERE r.messageId = :id AND r.expected)
          AND NOT EXISTS (SELECT 1 FROM message_recipient r WHERE r.messageId = :id AND r.expected AND r.ackedAtMs IS NULL)
        """,
    )
    suspend fun markDeliveredIfComplete(id: String): Int

    @Transaction
    suspend fun recordAck(messageId: String, deviceId: String, atMs: Long) {
        if (get(messageId) == null) return
        if (ackExisting(messageId, deviceId, atMs) == 0) {
            insertRecipients(listOf(MessageRecipient(messageId, deviceId, expected = false, ackedAtMs = atMs)))
        }
        // A receipt proves the message left this phone even if the send callback was missed.
        markRelayed(messageId, atMs)
        markDeliveredIfComplete(messageId)
    }
}
