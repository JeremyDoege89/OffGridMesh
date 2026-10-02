package dev.offgridmesh.maps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dev.offgridmesh.data.MapPack
import dev.offgridmesh.data.MapPackDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Where packs are cut from: a planet-wide PMTiles archive served over plain HTTPS with range requests.
 * Open decision D5: production should set [PLANET_URL] to the team's own hosted copy; until then the
 * newest Protomaps daily build is used (fine for development, not something to ship against).
 */
object MapSource {
    const val PLANET_URL = ""
    private const val BUILDS_INDEX = "https://build-metadata.protomaps.dev/builds.json"
    private const val BUILDS_BASE = "https://build.protomaps.com/"

    suspend fun resolvePlanetUrl(): String = withContext(Dispatchers.IO) {
        if (PLANET_URL.isNotEmpty()) return@withContext PLANET_URL
        val c = URL(BUILDS_INDEX).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        try {
            val builds = JSONArray(c.inputStream.bufferedReader().readText())
            val key = (0 until builds.length()).map { builds.getJSONObject(it).getString("key") }.max()
            BUILDS_BASE + key
        } finally {
            c.disconnect()
        }
    }
}

class HttpRangeSource(private val url: String) : RangeSource {
    override suspend fun read(offset: Long, length: Long): ByteArray = withContext(Dispatchers.IO) {
        var failure: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(1_000L * attempt)
            try {
                return@withContext fetch(offset, length)
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure!!
    }

    private fun fetch(offset: Long, length: Long): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        try {
            if (c.responseCode != 206) throw IOException("HTTP ${c.responseCode} for range request")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}

/** MBTiles 1.3 writer (TMS row order), the on-disk pack format MapLibre reads via mbtiles://. */
class MbtilesWriter(file: File) : TileSink {
    private val db = SQLiteDatabase.openOrCreateDatabase(file, null)
    private var pending = 0

    init {
        db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
        db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        db.execSQL("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
        db.beginTransaction()
    }

    override fun put(z: Int, x: Int, y: Int, data: ByteArray) {
        db.insertWithOnConflict(
            "tiles", null,
            ContentValues().apply {
                put("zoom_level", z)
                put("tile_column", x)
                put("tile_row", (1 shl z) - 1 - y)
                put("tile_data", data)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (++pending >= 2_000) {
            db.setTransactionSuccessful()
            db.endTransaction()
            db.beginTransaction()
            pending = 0
        }
    }

    fun finish(metadata: Map<String, String>) {
        metadata.forEach { (k, v) -> db.insert("metadata", null, ContentValues().apply { put("name", k); put("value", v) }) }
        db.setTransactionSuccessful()
        db.endTransaction()
        db.close()
    }

    fun abort() {
        if (db.inTransaction()) db.endTransaction()
        db.close()
    }
}

sealed interface PackJob {
    data object Idle : PackJob
    data class Measuring(val text: String) : PackJob
    data class Ready(val name: String, val plan: ExtractPlan, val url: String) : PackJob {
        val sourceKey get() = url.substringAfterLast('/')
    }
    data class Downloading(val name: String, val doneBytes: Long, val totalBytes: Long) : PackJob
    data class Done(val name: String) : PackJob
    data class Failed(val message: String) : PackJob
}

/** Downloads, lists and deletes offline map packs (§6). One job at a time; app-scoped so it survives screens. */
class MapPackManager(context: Context, private val dao: MapPackDao) {
    private val dir = File(context.filesDir, "maps").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _job = MutableStateFlow<PackJob>(PackJob.Idle)
    val job: StateFlow<PackJob> = _job.asStateFlow()
    private var running: Job? = null

    val packs: Flow<List<MapPack>> = dao.observeAll()

    fun file(pack: MapPack) = File(dir, pack.fileName)

    /** Step 1: find every tile in [bbox] and the exact download size, without downloading tiles. */
    fun measure(bbox: Bbox, name: String) {
        running?.cancel()
        running = scope.launch {
            try {
                _job.value = PackJob.Measuring("Contacting map server…")
                val url = MapSource.resolvePlanetUrl()
                val plan = PmtilesExtractor(HttpRangeSource(url)).plan(bbox, MIN_ZOOM, MAX_ZOOM) {
                    _job.value = PackJob.Measuring(it)
                }
                _job.value = PackJob.Ready(name, plan, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooLargeException) {
                _job.value = PackJob.Failed("That area is too large for one pack. Zoom in to a smaller area.")
            } catch (e: Exception) {
                Log.w(TAG, "measure failed", e)
                _job.value = PackJob.Failed(
                    "Couldn't reach the map server. Map packs can only be downloaded with an internet connection.",
                )
            }
        }
    }

    /** Step 2: download the measured area into a new pack. */
    fun download() {
        val ready = _job.value as? PackJob.Ready ?: return
        running?.cancel()
        running = scope.launch {
            val id = UUID.randomUUID().toString()
            val part = File(dir, "$id.mbtiles.part")
            var writer: MbtilesWriter? = null
            try {
                val plan = ready.plan
                _job.value = PackJob.Downloading(ready.name, 0, plan.downloadBytes)
                writer = MbtilesWriter(part)
                // Offsets in the plan belong to the exact archive that was measured.
                PmtilesExtractor(HttpRangeSource(ready.url)).download(plan, writer) { done ->
                    _job.value = PackJob.Downloading(ready.name, done, plan.downloadBytes)
                }
                val b = plan.bbox
                writer.finish(
                    mapOf(
                        "name" to ready.name,
                        "format" to "pbf",
                        "type" to "baselayer",
                        "version" to "1",
                        "bounds" to "${b.west},${b.south},${b.east},${b.north}",
                        "center" to "${(b.west + b.east) / 2},${(b.south + b.north) / 2},${plan.maxZoom - 3}",
                        "minzoom" to plan.minZoom.toString(),
                        "maxzoom" to plan.maxZoom.toString(),
                        "attribution" to ATTRIBUTION,
                        "json" to vectorLayersJson(plan.metadataJson),
                    ),
                )
                writer = null
                val final = File(dir, "$id.mbtiles")
                if (!part.renameTo(final)) throw IOException("rename failed")
                dao.insert(
                    MapPack(
                        id = id, name = ready.name, fileName = final.name, sizeBytes = final.length(),
                        minZoom = plan.minZoom, maxZoom = plan.maxZoom,
                        west = b.west, south = b.south, east = b.east, north = b.north,
                        createdAtMs = System.currentTimeMillis(), source = ready.sourceKey,
                    ),
                )
                _job.value = PackJob.Done(ready.name)
            } catch (e: CancellationException) {
                writer?.abort()
                part.delete()
                _job.value = PackJob.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                writer?.abort()
                part.delete()
                _job.value = PackJob.Failed("The download stopped (${e.message ?: "network error"}). Try again on Wi-Fi.")
            }
        }
    }

    fun cancel() {
        running?.cancel()
        running = null
        _job.value = PackJob.Idle
    }

    /** Clears a finished or failed job so the next screen starts fresh. */
    fun reset() {
        if (_job.value is PackJob.Done || _job.value is PackJob.Failed) _job.value = PackJob.Idle
    }

    suspend fun delete(pack: MapPack) = withContext(Dispatchers.IO) {
        file(pack).delete()
        dao.delete(pack)
    }

    companion object {
        private const val TAG = "MapPacks"

        /** z0–z9 are cheap overview levels; z10–z15 is the field range (§6). z15 is the source maximum. */
        const val MIN_ZOOM = 0
        const val MAX_ZOOM = 15

        const val ATTRIBUTION = "© OpenStreetMap contributors · Protomaps"

        private fun vectorLayersJson(metadata: String): String = try {
            JSONObject().put("vector_layers", JSONObject(metadata).getJSONArray("vector_layers")).toString()
        } catch (_: Exception) {
            "{}"
        }
    }
}
