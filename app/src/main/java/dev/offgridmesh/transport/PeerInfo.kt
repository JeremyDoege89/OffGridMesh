package dev.offgridmesh.transport

import java.nio.ByteBuffer
import java.util.UUID

/**
 * What a device advertises before any connection exists: enough to name it, break connect ties, and tell
 * whether it is on our team ([teamTag]: 0 = open mesh; null = an old build that predates teams).
 */
data class PeerInfo(val deviceId: String, val displayName: String, val teamTag: Int? = 0)

/**
 * Advertised endpoint-info bytes: `[version:1=2][deviceId uuid:16][teamTag:4][displayName utf-8 ≤ MAX_NAME_BYTES]`.
 * Kept small because BLE advertisements are; the full name arrives with the first presence beacon.
 * Version 1 (M1–M3 builds, no team tag) still decodes, with a null tag, so it is recognised and not joined.
 */
object PeerInfoCodec {
    const val VERSION: Byte = 2
    private const val V1: Byte = 1
    const val MAX_NAME_BYTES = 32

    fun encode(info: PeerInfo): ByteArray {
        val uuid = UUID.fromString(info.deviceId)
        val name = truncateUtf8(info.displayName, MAX_NAME_BYTES)
        return ByteBuffer.allocate(1 + 16 + 4 + name.size)
            .put(VERSION)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .putInt(info.teamTag ?: 0)
            .put(name)
            .array()
    }

    fun decode(bytes: ByteArray?): PeerInfo? {
        if (bytes == null || bytes.size < 17) return null
        val buf = ByteBuffer.wrap(bytes)
        return when (buf.get()) {
            V1 -> {
                val id = UUID(buf.long, buf.long).toString()
                PeerInfo(id, String(bytes, 17, bytes.size - 17, Charsets.UTF_8), teamTag = null)
            }
            VERSION -> {
                if (bytes.size < 21) return null
                val id = UUID(buf.long, buf.long).toString()
                val tag = buf.int
                PeerInfo(id, String(bytes, 21, bytes.size - 21, Charsets.UTF_8), tag)
            }
            else -> null
        }
    }

    /** Cuts on a code-point boundary so the receiver never sees a broken character. */
    internal fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val b = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + b.size > maxBytes) break
            out.write(b)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }
}
