package dev.offgridmesh

import dev.offgridmesh.sync.Envelope
import dev.offgridmesh.sync.ObjectType
import dev.offgridmesh.sync.Presence
import dev.offgridmesh.sync.WireCodec
import dev.offgridmesh.transport.PeerInfo
import dev.offgridmesh.transport.PeerInfoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CodecTest {
    private val id = UUID.randomUUID().toString()

    @Test fun peerInfoRoundTrip() {
        val info = PeerInfo(id, "Maya")
        assertEquals(info, PeerInfoCodec.decode(PeerInfoCodec.encode(info)))
    }

    @Test fun peerInfoTruncatesOnCodePointBoundary() {
        val long = "Ä".repeat(40) + "🔥"
        val bytes = PeerInfoCodec.encode(PeerInfo(id, long))
        assertTrue(bytes.size <= 17 + PeerInfoCodec.MAX_NAME_BYTES)
        val name = PeerInfoCodec.decode(bytes)!!.displayName
        assertEquals("Ä".repeat(16), name)
    }

    @Test fun peerInfoRejectsForeignBytes() {
        assertNull(PeerInfoCodec.decode(null))
        assertNull(PeerInfoCodec.decode(byteArrayOf(1, 2, 3)))
        val wrongVersion = PeerInfoCodec.encode(PeerInfo(id, "x")).also { it[0] = 9 }
        assertNull(PeerInfoCodec.decode(wrongVersion))
    }

    @Test fun envelopeRoundTrip() {
        val env = Envelope(ObjectType.MESSAGE, "m1", id, 1234L, 3, byteArrayOf(1, 2, 3))
        val back = WireCodec.decode(WireCodec.encode(env))!!
        assertEquals(ObjectType.MESSAGE, back.objectType)
        assertEquals("m1", back.objectId)
        assertEquals(id, back.senderId)
        assertEquals(1234L, back.updatedAtMs)
        assertEquals(3, back.ttlHops)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.payload)
    }

    /** Field and enum numbers are an API (Appendix B): this pins the exact bytes so a renumber fails here. */
    @Test fun envelopeWireFormatIsFrozen() {
        val env = Envelope(ObjectType.PRESENCE, "a", "b", 1L, 0, byteArrayOf(7))
        val hex = WireCodec.encode(env).joinToString("") { "%02x".format(it) }
        assertEquals("0806" + "12016" + "1" + "1a0162" + "2001" + "2800" + "320107", hex)
    }

    @Test fun presenceRoundTripWithOptionalFields() {
        val p = Presence(id, "Maya", 99L, batteryPct = 54)
        val env = WireCodec.presenceEnvelope(p)
        assertEquals(p, WireCodec.decodePresence(WireCodec.decode(WireCodec.encode(env))!!.payload))
        assertEquals(Presence(id, "Maya", 99L), WireCodec.decodePresence(WireCodec.encode(WireCodec.presenceEnvelope(Presence(id, "Maya", 99L))).let { WireCodec.decode(it)!!.payload }))
    }

    @Test fun garbageNeverThrows() {
        assertNull(WireCodec.decode(byteArrayOf(-1, -1, -1, -1)))
        assertNull(WireCodec.decode(ByteArray(0)))
    }
}
