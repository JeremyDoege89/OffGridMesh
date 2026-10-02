package dev.offgridmesh

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offgridmesh.data.MeshDatabase
import dev.offgridmesh.data.MessageEntity
import dev.offgridmesh.data.MessageRecipient
import dev.offgridmesh.data.MessageState
import dev.offgridmesh.data.Node
import dev.offgridmesh.data.RoomSyncSink
import dev.offgridmesh.sync.ChatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(ctx, MeshDatabase::class.java).build()
    private val me = "self"

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MeshDatabase::class.java)

    @After fun close() = db.close()

    private suspend fun own(id: String, recipients: List<String>) {
        db.messages().insert(MessageEntity(id, me, "Me", 1, "hi", 3, MessageState.LOCAL_ONLY, "main", receivedAtMs = 1))
        db.messages().insertRecipients(recipients.map { MessageRecipient(id, it, expected = true, ackedAtMs = null) })
    }

    private suspend fun state(id: String) = db.messages().get(id)!!.state

    @Test fun deliveredOnlyWhenEveryExpectedMemberAcks() = runTest {
        val sink = RoomSyncSink(db, me)
        own("m", listOf("b", "c"))
        sink.relayed("m", 2)
        assertEquals(MessageState.RELAYED, state("m"))
        sink.ack("m", "b", 3)
        assertEquals(MessageState.RELAYED, state("m"))
        sink.ack("x-latecomer", "z", 3) // receipt for an unknown message: ignored
        sink.ack("m", "c", 4)
        assertEquals(MessageState.DELIVERED, state("m"))
        val row = db.messages().observeChannel("main").first().single()
        assertEquals(2, row.expectedCount)
        assertEquals(2, row.ackedCount)
    }

    @Test fun lateJoinerReceiptDoesNotCountTowardDelivered() = runTest {
        val sink = RoomSyncSink(db, me)
        own("m", listOf("b"))
        sink.ack("m", "late", 3)
        assertEquals("a receipt proves it left the phone", MessageState.RELAYED, state("m"))
        sink.ack("m", "b", 4)
        assertEquals(MessageState.DELIVERED, state("m"))
    }

    @Test fun composedAloneSnapshotsRecipientsWhenFirstRelayed() = runTest {
        val sink = RoomSyncSink(db, me)
        own("m", emptyList())
        db.nodes().upsert(Node("b", "Bravo", lastSeenMs = System.currentTimeMillis(), hopCount = 0, batteryPct = null))
        sink.relayed("m", System.currentTimeMillis())
        assertEquals(1, db.messages().expectedCount("m"))
    }

    @Test fun duplicateReceivedMessageInsertsOnce() = runTest {
        var notified = 0
        val sink = RoomSyncSink(db, me, onNewMessage = { notified++ })
        val m = ChatMessage("r", "b", "Bravo", 5, "hello")
        assertTrue(sink.message(m, 0, null, 6))
        assertFalse(sink.message(m, 1, "c", 7))
        assertEquals(1, notified)
        assertEquals(1, db.messages().observeChannel("main").first().size)
    }

    @Test fun pinLastWriteWinsWithEditorTieBreak() = runTest {
        val base = dev.offgridmesh.data.PinEntity("p", 1.0, 2.0, "A", dev.offgridmesh.sync.PinType.HAZARD, "c", "C", 1, 10, "b", false)
        assertTrue(db.pins().applyIfNewer(base))
        assertFalse("older loses", db.pins().applyIfNewer(base.copy(label = "old", updatedAtMs = 5)))
        assertFalse("same version, lower editor loses", db.pins().applyIfNewer(base.copy(label = "x", editorId = "a")))
        assertTrue("same version, higher editor wins", db.pins().applyIfNewer(base.copy(label = "y", editorId = "z")))
        assertTrue(db.pins().applyIfNewer(base.copy(label = "gone", updatedAtMs = 20, deleted = true)))
        assertTrue(db.pins().observeLive().first().isEmpty())
        assertEquals(1, db.pins().pruneTombstones(21))
    }

    @Test fun migrate2To3KeepsMessagesAndNodes() {
        migrations.createDatabase("mig3.db", 2).use {
            it.execSQL("INSERT INTO node VALUES ('b', 'Bravo', 10, 0, 41)")
            it.execSQL("INSERT INTO message (id, senderId, senderName, timestampMs, text, ttlHops, state, channelId, relays, receivedAtMs) VALUES ('m', 'b', 'Bravo', 1, 'hi', 3, 'RECEIVED', 'main', 0, 2)")
            it.execSQL("INSERT INTO gossip VALUES ('m', 'MESSAGE', 'b', 1, 2, 1, x'00', 2)")
        }
        migrations.runMigrationsAndValidate("mig3.db", 3, true).use {
            it.query("SELECT COUNT(*) FROM message").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            it.query("SELECT deleted FROM gossip").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            it.query("SELECT lat FROM node").use { c -> c.moveToFirst(); assertTrue(c.isNull(0)) }
        }
    }

    @Test fun statusTimestampIsTheWholeRule() = runTest {
        val dao = db.statuses()
        val help = dev.offgridmesh.data.MemberStatusEntity("b", "B", dev.offgridmesh.sync.MemberState.NEED_HELP, 50, "ankle", null, null)
        assertTrue(dao.applyIfNewer(help).first)
        assertFalse("stale OK loses", dao.applyIfNewer(help.copy(state = dev.offgridmesh.sync.MemberState.OK, updatedAtMs = 40)).first)
        val (applied, old) = dao.applyIfNewer(help.copy(state = dev.offgridmesh.sync.MemberState.OK, updatedAtMs = 60))
        assertTrue(applied)
        assertEquals(dev.offgridmesh.sync.MemberState.NEED_HELP, old!!.state)
    }

    @Test fun checklistProgressCountsOnlyLiveItems() = runTest {
        val c = db.checklists()
        c.upsertList(dev.offgridmesh.data.ChecklistEntity("l", "Camp", "me", 1, 1, "me", false))
        fun item(id: String, done: Boolean, deleted: Boolean = false) =
            dev.offgridmesh.data.ChecklistItemEntity(id, "l", id, done, null, null, null, 1, 1, "me", deleted)
        c.upsertItem(item("a", true)); c.upsertItem(item("b", false)); c.upsertItem(item("x", true, deleted = true))
        val row = c.observeLists().first().single()
        assertEquals(2, row.total)
        assertEquals(1, row.done)
        assertFalse(c.applyItemIfNewer(item("a", false).copy(updatedAtMs = 1, editorId = "a-lower")))
    }

    @Test fun migrate3To4AddsChecklistAndStatusTables() {
        migrations.createDatabase("mig4.db", 3).use {
            it.execSQL("INSERT INTO node (deviceId, displayName, lastSeenMs, hopCount, batteryPct) VALUES ('b', 'Bravo', 10, 0, 41)")
        }
        migrations.runMigrationsAndValidate("mig4.db", 4, true).use {
            it.query("SELECT COUNT(*) FROM member_status").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            it.query("SELECT displayName FROM node").use { c -> c.moveToFirst(); assertEquals("Bravo", c.getString(0)) }
        }
    }

    @Test fun migrate1To2KeepsNodes() {
        migrations.createDatabase("mig.db", 1).use {
            it.execSQL("INSERT INTO node VALUES ('b', 'Bravo', 10, 0, 41)")
        }
        migrations.runMigrationsAndValidate("mig.db", 2, true).use {
            it.query("SELECT batteryPct FROM node WHERE deviceId = 'b'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(41, c.getInt(0))
            }
        }
    }
}
