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
        val sink = RoomSyncSink(db, me) { notified++ }
        val m = ChatMessage("r", "b", "Bravo", 5, "hello")
        assertTrue(sink.message(m, 0, null, 6))
        assertFalse(sink.message(m, 1, "c", 7))
        assertEquals(1, notified)
        assertEquals(1, db.messages().observeChannel("main").first().size)
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
