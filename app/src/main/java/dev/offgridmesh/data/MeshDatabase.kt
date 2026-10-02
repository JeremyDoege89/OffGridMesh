package dev.offgridmesh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Single source of truth (§4). IDs are device-generated UUIDs everywhere — no autoincrement (§5). */
@Database(entities = [Node::class], version = 1, exportSchema = true)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun nodes(): NodeDao

    companion object {
        fun create(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "mesh.db").build()
    }
}
