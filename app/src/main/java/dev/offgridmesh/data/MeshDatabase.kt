package dev.offgridmesh.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.offgridmesh.sync.GossipRecord

/** Single source of truth (§4). IDs are device-generated UUIDs everywhere — no autoincrement (§5). */
@Database(
    entities = [Node::class, GossipRecord::class, MessageEntity::class, MessageRecipient::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun nodes(): NodeDao
    abstract fun gossip(): GossipDao
    abstract fun messages(): MessageDao

    companion object {
        fun create(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "mesh.db").build()
    }
}
