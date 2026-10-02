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

    fun presenceEnvelope(p: Presence): Envelope = Envelope(
        objectType = ObjectType.PRESENCE,
        objectId = p.deviceId,
        senderId = p.deviceId,
        updatedAtMs = p.timestampMs,
        ttlHops = 0, // M1: direct only. M2 relays presence and derives hop counts from the path.
        payload = proto.encodeToByteArray(p),
    )

    fun decodePresence(payload: ByteArray): Presence? = try {
        proto.decodeFromByteArray<Presence>(payload)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
