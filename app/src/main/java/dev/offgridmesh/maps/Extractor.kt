package dev.offgridmesh.maps

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Byte-range reads from a PMTiles archive (HTTP in the app, a byte array in tests). */
fun interface RangeSource {
    suspend fun read(offset: Long, length: Long): ByteArray
}

data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(lat: Double, lng: Double) = lat in south..north && lng in west..east
}

data class TileRef(val z: Int, val x: Int, val y: Int, val offset: Long, val length: Int)

data class ExtractPlan(
    val header: Pmtiles.Header,
    val bbox: Bbox,
    val minZoom: Int,
    val maxZoom: Int,
    val tiles: List<TileRef>,
    val metadataJson: String,
) {
    /** Bytes to download — exact, since every tile's length is known from the directories. */
    val downloadBytes: Long = tiles.distinctBy { it.offset }.sumOf { it.length.toLong() }
}

/** Where extracted tiles go (an MBTiles file in the app). Calls are serialized by the extractor. */
interface TileSink {
    fun put(z: Int, x: Int, y: Int, data: ByteArray)
}

class TooLargeException(val tiles: Long) : Exception("area too large: $tiles tiles")

/**
 * Cuts a region out of a (planet-sized) remote PMTiles archive: the same job as `pmtiles extract`, done
 * on the phone so a pack can be any area the user frames before leaving Wi-Fi.
 */
class PmtilesExtractor(private val source: RangeSource, private val parallelism: Int = 6) {

    suspend fun plan(bbox: Bbox, minZoom: Int, maxZoom: Int, onProgress: (String) -> Unit = {}): ExtractPlan {
        val first = source.read(0, Pmtiles.INITIAL_FETCH.toLong())
        val header = Pmtiles.parseHeader(first)
        val minZ = minZoom.coerceAtLeast(header.minZoom)
        val maxZ = maxZoom.coerceAtMost(header.maxZoom)

        val wanted = wantedTileIds(bbox, minZ, maxZ)
        onProgress("Reading map index…")
        val rootBytes = if (header.rootOffset + header.rootLength <= first.size) {
            first.copyOfRange(header.rootOffset.toInt(), (header.rootOffset + header.rootLength).toInt())
        } else {
            source.read(header.rootOffset, header.rootLength)
        }
        val root = Pmtiles.parseDirectory(Pmtiles.decompress(rootBytes, header.internalCompression))

        val found = mutableListOf<TileRef>()
        val lock = Mutex()
        val gate = Semaphore(parallelism)
        var leavesRead = 0

        suspend fun resolve(entries: List<Pmtiles.Entry>, ids: LongArray) {
            val groups = groupByEntry(entries, ids)
            coroutineScope {
                groups.map { (index, group) ->
                    async {
                        val e = entries[index]
                        if (e.runLength > 0) {
                            val hits = group.filter { it < e.tileId + e.runLength }.map { id ->
                                val (z, x, y) = Pmtiles.tileIdToZxy(id)
                                TileRef(z, x, y, header.tileDataOffset + e.offset, e.length)
                            }
                            lock.withLock { found += hits }
                        } else {
                            val leafBytes = gate.withPermit { source.read(header.leafOffset + e.offset, e.length.toLong()) }
                            val leaf = Pmtiles.parseDirectory(Pmtiles.decompress(leafBytes, header.internalCompression))
                            lock.withLock { leavesRead++ }
                            onProgress("Reading map index… ($leavesRead sections)")
                            resolve(leaf, group)
                        }
                    }
                }.awaitAll()
            }
        }
        resolve(root, wanted)

        val metadata = if (header.metadataLength > 0) {
            Pmtiles.decompress(source.read(header.metadataOffset, header.metadataLength), header.internalCompression)
                .toString(Charsets.UTF_8)
        } else {
            "{}"
        }
        return ExtractPlan(header, bbox, minZ, maxZ, found.sortedBy { it.offset }, metadata)
    }

    /** Downloads every tile in [plan] into [sink], coalescing nearby byte ranges into few requests. */
    suspend fun download(plan: ExtractPlan, sink: TileSink, onProgress: (bytesDone: Long) -> Unit = {}) {
        val byOffset = plan.tiles.groupBy { it.offset }.toSortedMap()
        val chunks = mutableListOf<List<Long>>()
        var current = mutableListOf<Long>()
        var start = 0L
        var end = 0L
        for ((offset, refs) in byOffset) {
            val len = refs.first().length
            if (current.isNotEmpty() && (offset - end > MAX_GAP || offset + len - start > MAX_REQUEST)) {
                chunks += current
                current = mutableListOf()
            }
            if (current.isEmpty()) start = offset
            current += offset
            end = maxOf(end, offset + len)
        }
        if (current.isNotEmpty()) chunks += current

        val writeLock = Mutex()
        val gate = Semaphore(parallelism)
        var done = 0L
        coroutineScope {
            chunks.map { offsets ->
                async {
                    val from = offsets.first()
                    val to = offsets.maxOf { it + byOffset.getValue(it).first().length }
                    val bytes = gate.withPermit { source.read(from, to - from) }
                    writeLock.withLock {
                        var chunkBytes = 0L
                        for (o in offsets) {
                            val refs = byOffset.getValue(o)
                            val start = (o - from).toInt()
                            val data = bytes.copyOfRange(start, start + refs.first().length)
                            refs.forEach { sink.put(it.z, it.x, it.y, data) }
                            chunkBytes += data.size
                        }
                        done += chunkBytes
                        onProgress(done)
                    }
                }
            }.awaitAll()
        }
    }

    companion object {
        /** Gap between two tiles' bytes worth fetching anyway to save a request. */
        const val MAX_GAP = 64L * 1024
        const val MAX_REQUEST = 8L * 1024 * 1024

        /** Guard against framing a continent: ~500k tiles is several GB. */
        const val MAX_TILES = 500_000L

        fun tileRange(bbox: Bbox, z: Int): IntArray {
            val n = 1 shl z
            fun x(lng: Double) = floor((lng + 180) / 360 * n).toInt().coerceIn(0, n - 1)
            fun y(lat: Double): Int {
                val r = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180
                return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
            }
            return intArrayOf(x(bbox.west), y(bbox.north), x(bbox.east), y(bbox.south))
        }

        fun tileCount(bbox: Bbox, minZ: Int, maxZ: Int): Long = (minZ..maxZ).sumOf { z ->
            val (x0, y0, x1, y1) = tileRange(bbox, z)
            (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
        }

        fun wantedTileIds(bbox: Bbox, minZ: Int, maxZ: Int): LongArray {
            val count = tileCount(bbox, minZ, maxZ)
            if (count > MAX_TILES) throw TooLargeException(count)
            val out = LongArray(count.toInt())
            var i = 0
            for (z in minZ..maxZ) {
                val (x0, y0, x1, y1) = tileRange(bbox, z)
                for (x in x0..x1) for (y in y0..y1) out[i++] = Pmtiles.zxyToTileId(z, x, y)
            }
            out.sort()
            return out
        }

        /** Pairs each directory entry with the wanted ids that fall in its range (both inputs sorted). */
        internal fun groupByEntry(entries: List<Pmtiles.Entry>, ids: LongArray): List<Pair<Int, LongArray>> {
            val out = mutableListOf<Pair<Int, LongArray>>()
            var i = 0
            while (i < ids.size) {
                val e = Pmtiles.findEntry(entries, ids[i])
                if (e < 0) {
                    i++
                    continue
                }
                val nextStart = entries.getOrNull(e + 1)?.tileId ?: Long.MAX_VALUE
                var j = i
                while (j < ids.size && ids[j] < nextStart) j++
                out += e to ids.copyOfRange(i, j)
                i = j
            }
            return out
        }
    }
}
