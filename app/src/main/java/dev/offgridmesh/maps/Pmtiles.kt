package dev.offgridmesh.maps

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Just enough of PMTiles v3 (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md) to read
 * directories and locate tiles in a remote archive. Pure JVM, so it is unit-tested without a device.
 */
object Pmtiles {
    const val HEADER_BYTES = 127

    /** First request size: the spec guarantees header + root directory fit in 16 KiB. */
    const val INITIAL_FETCH = 16_384

    enum class Compression { UNKNOWN, NONE, GZIP, BROTLI, ZSTD }

    data class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafOffset: Long,
        val leafLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val internalCompression: Compression,
        val tileCompression: Compression,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
    )

    /** runLength 0 = a leaf directory pointer; otherwise [runLength] consecutive tile ids share the data. */
    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    fun parseHeader(bytes: ByteArray): Header {
        require(bytes.size >= HEADER_BYTES) { "short header" }
        require(String(bytes, 0, 7, Charsets.US_ASCII) == "PMTiles" && bytes[7].toInt() == 3) { "not PMTiles v3" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun comp(i: Int) = Compression.entries.getOrElse(bytes[i].toInt()) { Compression.UNKNOWN }
        return Header(
            rootOffset = b.getLong(8),
            rootLength = b.getLong(16),
            metadataOffset = b.getLong(24),
            metadataLength = b.getLong(32),
            leafOffset = b.getLong(40),
            leafLength = b.getLong(48),
            tileDataOffset = b.getLong(56),
            tileDataLength = b.getLong(64),
            internalCompression = comp(97),
            tileCompression = comp(98),
            tileType = bytes[99].toInt(),
            minZoom = bytes[100].toInt(),
            maxZoom = bytes[101].toInt(),
        )
    }

    fun decompress(bytes: ByteArray, c: Compression): ByteArray = when (c) {
        Compression.NONE -> bytes
        Compression.GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else -> throw IllegalArgumentException("unsupported compression $c")
    }

    fun parseDirectory(bytes: ByteArray): List<Entry> {
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) {
            last += varint()
            ids[i] = last
        }
        val runs = IntArray(n) { varint().toInt() }
        val lengths = IntArray(n) { varint().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = varint()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    /** Hilbert tile id (spec §"Tile IDs"): z0..z-1 tiles come first, then the Hilbert index at z. */
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        if (z == 0) return 0
        val acc = ((1L shl (2 * z)) - 1) / 3
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = 1L shl (z - 1)
        while (s > 0) {
            val rx = if (tx and s != 0L) 1L else 0L
            val ry = if (ty and s != 0L) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = s - 1 - tx
                    ty = s - 1 - ty
                }
                val t = tx; tx = ty; ty = t
            }
            s = s shr 1
        }
        return acc + d
    }

    fun tileIdToZxy(id: Long): Triple<Int, Int, Int> {
        var acc = 0L
        var z = 0
        while (true) {
            val count = 1L shl (2 * z)
            if (acc + count > id) break
            acc += count
            z++
        }
        var t = id - acc
        var x = 0L
        var y = 0L
        var s = 1L
        val n = 1L shl z
        while (s < n) {
            val rx = 1L and (t / 2)
            val ry = 1L and (t xor rx)
            if (ry == 0L) {
                if (rx == 1L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val tmp = x; x = y; y = tmp
            }
            x += s * rx
            y += s * ry
            t /= 4
            s *= 2
        }
        return Triple(z, x.toInt(), y.toInt())
    }

    /** Index of the entry whose range could hold [tileId] (largest entry.tileId ≤ tileId), or -1. */
    fun findEntry(entries: List<Entry>, tileId: Long): Int {
        var lo = 0
        var hi = entries.lastIndex
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].tileId <= tileId) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    // Test helper: the inverse of parseDirectory.
    internal fun encodeDirectory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x >= 0x80) {
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }
        varint(entries.size.toLong())
        var last = 0L
        entries.forEach { varint(it.tileId - last); last = it.tileId }
        entries.forEach { varint(it.runLength.toLong()) }
        entries.forEach { varint(it.length.toLong()) }
        entries.forEachIndexed { i, e ->
            val contiguous = i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length
            varint(if (contiguous) 0 else e.offset + 1)
        }
        return out.toByteArray()
    }
}
