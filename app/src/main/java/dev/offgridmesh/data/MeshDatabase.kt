package dev.offgridmesh.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.offgridmesh.sync.GossipRecord

/** Single source of truth (§4). IDs are device-generated UUIDs everywhere — no autoincrement (§5). */
@Database(
    entities = [
        Node::class, GossipRecord::class, MessageEntity::class, MessageRecipient::class, PinEntity::class, MapPack::class,
        ClampedEdit::class, ChecklistEntity::class, ChecklistItemEntity::class, MemberStatusEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun nodes(): NodeDao
    abstract fun gossip(): GossipDao
    abstract fun messages(): MessageDao
    abstract fun pins(): PinDao
    abstract fun mapPacks(): MapPackDao
    abstract fun checklists(): ChecklistDao
    abstract fun statuses(): MemberStatusDao

    companion object {
        fun create(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "mesh.db").build()
    }
}
