package dev.offgridmesh

import dev.offgridmesh.maps.Bbox
import dev.offgridmesh.maps.Pmtiles
import dev.offgridmesh.maps.PmtilesExtractor
import dev.offgridmesh.maps.TileSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class PmtilesTest {
    @Test fun tileIdsMatchSpecVectors() {
        assertEquals(0L, Pmtiles.zxyToTileId(0, 0, 0))
        assertEquals(1L, Pmtiles.zxyToTileId(1, 0, 0))
        assertEquals(2L, Pmtiles.zxyToTileId(1, 0, 1))
        assertEquals(3L, Pmtiles.zxyToTileId(1, 1, 1))
        assertEquals(4L, Pmtiles.zxyToTileId(1, 1, 0))
        assertEquals(5L, Pmtiles.zxyToTileId(2, 0, 0))
        assertEquals(19078479L, Pmtiles.zxyToTileId(12, 3423, 1763))
    }

    @Test fun tileIdRoundTripsAtEveryZoom() {
        for (z in 0..15) {
            val n = 1 shl z
            for ((x, y) in listOf(0 to 0, n - 1 to 0, 0 to n - 1, n - 1 to n - 1, n / 3 to n / 7)) {
                assertEquals(Triple(z, x, y), Pmtiles.tileIdToZxy(Pmtiles.zxyToTileId(z, x, y)))
            }
        }
    }

    @Test fun directoryRoundTrip() {
        val entries = listOf(
            Pmtiles.Entry(0, 0, 10, 1),
            Pmtiles.Entry(1, 10, 20, 1),
            Pmtiles.Entry(5, 100, 7, 3),
            Pmtiles.Entry(20, 500, 40, 0),
        )
        assertEquals(entries, Pmtiles.parseDirectory(Pmtiles.encodeDirectory(entries)))
    }

    /** A tiny archive: z0–z2 tiles in the root, z3 behind a leaf, z3 data run-length shared. */
    private fun archive(): Pair<ByteArray, Map<Long, ByteArray>> {
        val tiles = mutableMapOf<Long, ByteArray>()
        for (z in 0..2) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) {
            tiles[Pmtiles.zxyToTileId(z, x, y)] = "t$z/$x/$y".toByteArray()
        }
        val data = ByteArrayOutputStream()
        val rootEntries = mutableListOf<Pmtiles.Entry>()
        tiles.toSortedMap().forEach { (id, bytes) ->
            rootEntries += Pmtiles.Entry(id, data.size().toLong(), bytes.size, 1)
            data.write(bytes)
        }
        val shared = "ocean".toByteArray()
        val z3first = Pmtiles.zxyToTileId(3, 0, 0)
        val leaf = gzip(Pmtiles.encodeDirectory(listOf(Pmtiles.Entry(z3first, data.size().toLong(), shared.size, 64))))
        data.write(shared)
        rootEntries += Pmtiles.Entry(z3first, 0, leaf.size, 0)
        val root = gzip(Pmtiles.encodeDirectory(rootEntries))

        val rootOff = 127L
        val leafOff = rootOff + root.size
        val dataOff = leafOff + leaf.size
        val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PMTiles".toByteArray()); put(3)
            putLong(rootOff); putLong(root.size.toLong())
            putLong(0); putLong(0)
            putLong(leafOff); putLong(leaf.size.toLong())
            putLong(dataOff); putLong(data.size().toLong())
            position(97); put(2); put(1); put(1); put(0); put(3)
        }.array()
        val out = ByteArrayOutputStream().apply { write(header); write(root); write(leaf); write(data.toByteArray()) }
        for (z in 0..3) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) if (z == 3) {
            tiles[Pmtiles.zxyToTileId(z, x, y)] = shared
        }
        return out.toByteArray() to tiles
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test fun extractsExactlyTheTilesInTheBox() = runTest {
        val (bytes, tiles) = archive()
        var requests = 0
        val ex = PmtilesExtractor({ off, len ->
            requests++
            bytes.copyOfRange(off.toInt(), minOf(bytes.size.toLong(), off + len).toInt())
        })
        // North-west quadrant.
        val box = Bbox(-170.0, 10.0, -10.0, 80.0)
        val plan = ex.plan(box, 0, 3)
        val got = mutableMapOf<Triple<Int, Int, Int>, ByteArray>()
        ex.download(plan, object : TileSink {
            override fun put(z: Int, x: Int, y: Int, data: ByteArray) { got[Triple(z, x, y)] = data }
        })
        val expected = (0..3).flatMap { z ->
            val (x0, y0, x1, y1) = PmtilesExtractor.tileRange(box, z)
            (x0..x1).flatMap { x -> (y0..y1).map { y -> Triple(z, x, y) } }
        }.toSet()
        assertEquals(expected, got.keys)
        got.forEach { (k, v) -> assertArrayEquals(tiles[Pmtiles.zxyToTileId(k.first, k.second, k.third)], v) }
        assertEquals("z0 + z1 + 2×2 + 4×4", 1 + 1 + 4 + 16, got.size)
    }

    @Test fun tileRangeCoversKnownPlace() {
        // Point (-86.78, 36.16) at z12 → slippy tile x 1060, y 1606.
        val r = PmtilesExtractor.tileRange(Bbox(-86.78, 36.16, -86.78, 36.16), 12)
        assertEquals(listOf(1060, 1606, 1060, 1606), r.toList())
    }
}
