package dev.offgridmesh.transport

import java.nio.ByteBuffer
import java.util.UUID

/** What a device advertises before any connection exists: enough to name it and break connect ties. */
data class PeerInfo(val deviceId: String, val displayName: String)

/**
 * Advertised endpoint-info bytes: `[version:1][deviceId uuid:16][displayName utf-8 ≤ MAX_NAME_BYTES]`.
 * Kept small because BLE advertisements are; the full name arrives with the first presence beacon.
 */
object PeerInfoCodec {
    const val VERSION: Byte = 1
    const val MAX_NAME_BYTES = 32

    fun encode(info: PeerInfo): ByteArray {
        val uuid = UUID.fromString(info.deviceId)
        val name = truncateUtf8(info.displayName, MAX_NAME_BYTES)
        return ByteBuffer.allocate(1 + 16 + name.size)
            .put(VERSION)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .put(name)
            .array()
    }

    fun decode(bytes: ByteArray?): PeerInfo? {
        if (bytes == null || bytes.size < 17 || bytes[0] != VERSION) return null
        val buf = ByteBuffer.wrap(bytes)
        buf.get()
        val id = UUID(buf.long, buf.long).toString()
        val name = String(bytes, 17, bytes.size - 17, Charsets.UTF_8)
        return PeerInfo(id, name)
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
