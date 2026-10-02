package dev.offgridmesh.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.offgridmesh.maps.Bbox
import kotlinx.coroutines.flow.Flow

/** A downloaded offline map region (§6). Local to this phone; never gossiped. */
@Entity(tableName = "map_pack")
data class MapPack(
    @PrimaryKey val id: String,
    val name: String,
    /** MBTiles file name inside filesDir/maps. */
    val fileName: String,
    val sizeBytes: Long,
    val minZoom: Int,
    val maxZoom: Int,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val createdAtMs: Long,
    /** Which planet build the tiles came from, e.g. "20261002.pmtiles". */
    val source: String,
) {
    val bbox get() = Bbox(west, south, east, north)
}

@Dao
interface MapPackDao {
    @Query("SELECT * FROM map_pack ORDER BY createdAtMs")
    fun observeAll(): Flow<List<MapPack>>

    @Query("SELECT COUNT(*) FROM map_pack")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(p: MapPack)

    @Delete
    suspend fun delete(p: MapPack)
}
