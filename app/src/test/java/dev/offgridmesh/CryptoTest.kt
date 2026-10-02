package dev.offgridmesh

import dev.offgridmesh.crypto.AesGcmFrames
import dev.offgridmesh.crypto.OpenFrames
import dev.offgridmesh.crypto.TeamCrypto
import dev.offgridmesh.transport.PeerInfo
import dev.offgridmesh.transport.PeerInfoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CryptoTest {
    private val key = TeamCrypto.deriveKey("correct horse battery staple", iterations = 1_000)

    @Test fun derivationIsDeterministicAndNormalized() {
        val again = TeamCrypto.deriveKey("  correct horse battery staple ", iterations = 1_000)
        assertArrayEquals(key, again)
        // Composed vs decomposed é derive the same key (NFC).
        assertArrayEquals(
            TeamCrypto.deriveKey("caf\u00e9 team", iterations = 1_000),
            TeamCrypto.deriveKey("cafe\u0301 team", iterations = 1_000),
        )
        assertEquals(32, key.size)
    }

    @Test fun fullStrengthDerivationWorks() {
        assertEquals(32, TeamCrypto.deriveKey("correct horse battery staple").size)
    }

    @Test fun sealOpenRoundTripWithFreshNonces() {
        val f = AesGcmFrames(key)
        val a = f.seal("hello".toByteArray())
        val b = f.seal("hello".toByteArray())
        assertFalse("random nonce per frame", a.contentEquals(b))
        assertEquals("hello", String(f.open(a)!!))
    }

    @Test fun wrongKeyTamperingAndModeMixupsAreRejected() {
        val f = AesGcmFrames(key)
        val other = AesGcmFrames(TeamCrypto.deriveKey("another team", iterations = 1_000))
        val sealed = f.seal("pin".toByteArray())
        assertNull(other.open(sealed))
        assertNull(f.open(sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
        assertNull(f.open(OpenFrames.seal("plain".toByteArray())))
        assertNull(OpenFrames.open(sealed))
        assertNull(f.open(ByteArray(3)))
    }

    @Test fun teamTagsDifferAndNeverZero() {
        val t = TeamCrypto.teamTag(key)
        assertNotEquals(0, t)
        assertNotEquals(t, TeamCrypto.teamTag(TeamCrypto.deriveKey("another team", iterations = 1_000)))
        assertEquals(0, OpenFrames.teamTag)
    }

    @Test fun peerInfoV2CarriesTagAndV1IsRecognisedAsLegacy() {
        val id = "12345678-1234-1234-1234-123456789abc"
        assertEquals(PeerInfo(id, "Maya", 42), PeerInfoCodec.decode(PeerInfoCodec.encode(PeerInfo(id, "Maya", 42))))
        val v1 = byteArrayOf(1) + PeerInfoCodec.encode(PeerInfo(id, "Old", 0)).copyOfRange(1, 17) + "Old".toByteArray()
        assertEquals(PeerInfo(id, "Old", null), PeerInfoCodec.decode(v1))
    }
}
