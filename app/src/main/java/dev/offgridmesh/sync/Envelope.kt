@file:OptIn(ExperimentalSerializationApi::class)

package dev.offgridmesh.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * Wire format (Build Guide Appendix B), frozen in M1: kotlinx.serialization ProtoBuf.
 * Field numbers and enum numbers are an API — never renumber or reuse them; only add new ones.
 */

@Serializable
enum class ObjectType {
    @ProtoNumber(1) MESSAGE,
    @ProtoNumber(2) PIN,
    @ProtoNumber(3) CHECKLIST,
    @ProtoNumber(4) CHECKLIST_ITEM,
    @ProtoNumber(5) STATUS,
    @ProtoNumber(6) PRESENCE,
    /** Delivery receipt for a MESSAGE, gossiped back toward its author. */
    @ProtoNumber(7) ACK,
    /** Control frames between two directly connected peers; never stored or relayed. */
    @ProtoNumber(8) INVENTORY,
    @ProtoNumber(9) REQUEST,
}

@Serializable
class Envelope(
    @ProtoNumber(1) val objectType: ObjectType,
    /** Primary key of the enclosed row; the dedup key. */
    @ProtoNumber(2) val objectId: String,
    /** Originating deviceId — never the relaying device. */
    @ProtoNumber(3) val senderId: String,
    /** Author timestamp; the last-write-wins input. */
    @ProtoNumber(4) val updatedAtMs: Long,
    /** Decremented by each relay; dropped at 0. */
    @ProtoNumber(5) val ttlHops: Int,
    /** Serialized row delta. Encrypted from M4 (§10); plaintext in M1–M3. */
    @ProtoNumber(6) val payload: ByteArray,
    /** Times this envelope has been relayed (0 = straight from the author). Set by relays, not the author. */
    @ProtoNumber(7) val relays: Int = 0,
)

/** Presence beacon (Appendix B). hopCount is never carried — receivers compute it from the relay path. */
@Serializable
data class Presence(
    @ProtoNumber(1) val deviceId: String,
    @ProtoNumber(2) val displayName: String,
    @ProtoNumber(3) val timestampMs: Long,
    @ProtoNumber(4) val batteryPct: Int? = null,
    @ProtoNumber(5) val lat: Double? = null,
    @ProtoNumber(6) val lng: Double? = null,
)

/** Group chat message (§5.2). */
@Serializable
data class ChatMessage(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val senderId: String,
    @ProtoNumber(3) val senderName: String,
    @ProtoNumber(4) val timestampMs: Long,
    @ProtoNumber(5) val text: String,
    @ProtoNumber(6) val channelId: String = MAIN_CHANNEL,
) {
    companion object {
        const val MAIN_CHANNEL = "main"
        const val MAX_CHARS = 500
    }
}

/** "[ackerId] has [messageId]". The author counts these toward Delivered. */
@Serializable
data class Ack(
    @ProtoNumber(1) val messageId: String,
    @ProtoNumber(2) val authorId: String,
    @ProtoNumber(3) val ackerId: String,
    @ProtoNumber(4) val timestampMs: Long,
) {
    companion object {
        fun objectId(messageId: String, ackerId: String) = "ack:$messageId:$ackerId"
    }
}

@Serializable
data class InventoryEntry(
    @ProtoNumber(1) val objectId: String,
    @ProtoNumber(2) val updatedAtMs: Long,
)

/** One page of "what I can relay". Pages are independent: each gets its own REQUEST in reply. */
@Serializable
data class Inventory(@ProtoNumber(1) val entries: List<InventoryEntry> = emptyList())

@Serializable
data class Request(@ProtoNumber(1) val objectIds: List<String> = emptyList())

object WireCodec {
    private val proto = ProtoBuf

    fun encode(envelope: Envelope): ByteArray = proto.encodeToByteArray(envelope)

    /** Null for anything that isn't a well-formed envelope — a bad peer must never crash the mesh. */
    fun decode(bytes: ByteArray): Envelope? = try {
        proto.decodeFromByteArray<Envelope>(bytes)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    inline fun <reified T : Any> encodePayload(value: T): ByteArray = ProtoBuf.encodeToByteArray(value)

    /** Null for a malformed payload, like [decode]. */
    inline fun <reified T : Any> decodePayload(bytes: ByteArray): T? = try {
        ProtoBuf.decodeFromByteArray<T>(bytes)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun decodePresence(payload: ByteArray): Presence? = decodePayload<Presence>(payload)
}
