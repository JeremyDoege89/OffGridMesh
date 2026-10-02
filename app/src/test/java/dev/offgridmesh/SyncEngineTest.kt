package dev.offgridmesh

import dev.offgridmesh.mesh.MeshCore
import dev.offgridmesh.sync.Ack
import dev.offgridmesh.sync.ChatMessage
import dev.offgridmesh.sync.GossipRecord
import dev.offgridmesh.sync.GossipStore
import dev.offgridmesh.sync.InventoryEntry
import dev.offgridmesh.sync.ObjectType
import dev.offgridmesh.sync.Presence
import dev.offgridmesh.sync.SyncEngine
import dev.offgridmesh.sync.SyncSink
import dev.offgridmesh.sync.WireCodec
import dev.offgridmesh.transport.PeerInfo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemStore : GossipStore {
    val map = linkedMapOf<String, GossipRecord>()
    override suspend fun version(objectId: String) = map[objectId]?.updatedAtMs
    override suspend fun save(record: GossipRecord) { map[record.objectId] = record }
    override suspend fun get(objectId: String) = map[objectId]
    override suspend fun records(objectIds: List<String>) = objectIds.mapNotNull { map[it] }
    override suspend fun relayable(sinceMs: Long, limit: Int) = map.values
        .filter { it.outTtl > 0 && it.receivedAtMs >= sinceMs }
        .sortedByDescending { it.receivedAtMs }.take(limit)
        .map { InventoryEntry(it.objectId, it.updatedAtMs) }
}

private class MemSink : SyncSink {
    data class Got(val m: ChatMessage, val relays: Int, val via: String?)
    val messages = mutableListOf<Got>()
    val presence = mutableMapOf<String, Pair<Presence, Int>>()
    val acks = mutableSetOf<Pair<String, String>>()
    val relayed = mutableSetOf<String>()
    override suspend fun message(m: ChatMessage, relays: Int, viaDeviceId: String?, receivedAtMs: Long): Boolean {
        if (messages.any { it.m.id == m.id }) return false
        messages += Got(m, relays, viaDeviceId)
        return true
    }
    override suspend fun presence(p: Presence, relays: Int, seenAtMs: Long) { presence[p.deviceId] = p to relays }
    override suspend fun ack(messageId: String, ackerId: String, atMs: Long) { acks += messageId to ackerId }
    override suspend fun relayed(messageId: String, atMs: Long) { relayed += messageId }
}

class SyncEngineTest {
    private fun uuid(n: Int) = "00000000-0000-0000-0000-00000000000$n"

    private inner class Phone(val air: FakeAir, val name: String, val id: String, val scope: TestScope, var ttl: Int = 3) {
        val radio = FakeRadio(air, name)
        var store = MemStore()
        val sink = MemSink()
        lateinit var core: MeshCore
        lateinit var engine: SyncEngine

        fun boot() {
            core = MeshCore(radio, FakeNodes(), scope.backgroundScope, clock = { scope.testScheduler.currentTime })
            engine = SyncEngine(id, store, sink, core, clock = { scope.testScheduler.currentTime }, ttlHops = { ttl })
            core.listener = engine
            core.start(PeerInfo(id, name))
        }

        suspend fun say(text: String): String {
            val m = ChatMessage("msg-$name-$text", id, name, scope.testScheduler.currentTime, text)
            engine.originate(ObjectType.MESSAGE, m.id, m.timestampMs, WireCodec.encodePayload(m))
            return m.id
        }
    }

    /** A–B–C in a line: A and C can't hear each other. */
    private fun TestScope.line(vararg names: String): List<Phone> {
        val air = FakeAir()
        val phones = names.mapIndexed { i, n -> Phone(air, n, uuid(i + 1), this) }
        phones.forEachIndexed { i, p ->
            p.radio.reachable = setOfNotNull(phones.getOrNull(i - 1)?.radio, phones.getOrNull(i + 1)?.radio)
        }
        return phones
    }

    @Test fun messageCrossesTwoHopsAndComesBackDelivered() = runTest {
        val (a, b, c) = line("A", "B", "C")
        listOf(a, b, c).forEach { it.boot() }
        runCurrent()
        assertTrue(a.core.state.value.connectedDeviceIds == setOf(b.id))

        val id = a.say("generator fueled?")
        runCurrent()

        val atC = c.sink.messages.single()
        assertEquals("generator fueled?", atC.m.text)
        assertEquals("one relay between A and C", 1, atC.relays)
        assertEquals(b.id, atC.via)
        assertEquals(0, b.sink.messages.single().relays)
        assertTrue(id in a.sink.relayed)
        assertEquals("receipts from both B and C reach the author", setOf(id to b.id, id to c.id), a.sink.acks)
    }

    @Test fun ttlLimitsHops() = runTest {
        val (a, b, c, d) = line("A", "B", "C", "D")
        listOf(a, b, c, d).forEach { it.boot() }
        runCurrent()
        a.ttl = 2
        a.say("short range")
        runCurrent()
        assertEquals(1, b.sink.messages.size)
        assertEquals(1, c.sink.messages.size)
        assertTrue("TTL 2 stops after two hops", d.sink.messages.isEmpty())
    }

    @Test fun duplicatesNeverDeliverTwice() = runTest {
        // Triangle: every phone hears every other, so each message arrives by two paths.
        val air = FakeAir()
        val phones = listOf("A", "B", "C").mapIndexed { i, n -> Phone(air, n, uuid(i + 1), this) }
        phones.forEach { it.boot() }
        runCurrent()
        phones[0].say("hello")
        runCurrent()
        assertEquals(1, phones[1].sink.messages.size)
        assertEquals(1, phones[2].sink.messages.size)
    }

    @Test fun queuedWhileAloneSendsItselfOnConnect() = runTest {
        val (a, b) = line("A", "B")
        a.boot()
        runCurrent()
        val id = a.say("written out of range")
        runCurrent()
        assertTrue("nobody had it, so it is not relayed yet", id !in a.sink.relayed)

        b.boot()
        runCurrent()
        assertEquals("written out of range", b.sink.messages.single().m.text)
        assertTrue(id in a.sink.relayed)
    }

    @Test fun relayKilledMidTransitStillDelivers() = runTest {
        val (a, b, c) = line("A", "B", "C")
        a.boot(); b.boot()
        runCurrent()
        a.say("via B")
        runCurrent()
        assertEquals(1, b.sink.messages.size)

        // B's process dies before C is in range; its queue is whatever is in its store.
        b.core.stop()
        val survived = b.store
        runCurrent()
        b.store = survived
        b.boot()
        c.boot()
        runCurrent()
        assertEquals("via B", c.sink.messages.single().m.text)
    }

    @Test fun presenceRelaysWithHopCount() = runTest {
        val (a, b, c) = line("A", "B", "C")
        listOf(a, b, c).forEach { it.boot() }
        runCurrent()
        a.engine.originatePresence("Alpha", 77)
        runCurrent()
        assertEquals(0, b.sink.presence[a.id]!!.second)
        val (p, relays) = c.sink.presence[a.id]!!
        assertEquals(1, relays)
        assertEquals(77, p.batteryPct)
    }

    @Test fun newerPresenceReplacesOlderButNotTheReverse() = runTest {
        val (a, b) = line("A", "B")
        a.boot(); b.boot()
        runCurrent()
        a.engine.originatePresence("Alpha", 50)
        runCurrent()
        testScheduler.advanceTimeBy(1_000)
        a.engine.originatePresence("Alpha renamed", 49)
        runCurrent()
        assertEquals("Alpha renamed", b.sink.presence[a.id]!!.first.displayName)
    }

    @Test fun forgedAckForSomeoneElseIsIgnored() = runTest {
        val (a, b) = line("A", "B")
        a.boot(); b.boot()
        runCurrent()
        // B claims C acknowledged A's message — the acker must be the envelope's sender.
        val ack = Ack("m1", a.id, uuid(9), 0)
        b.engine.originate(ObjectType.ACK, Ack.objectId("m1", uuid(9)), 0, WireCodec.encodePayload(ack))
        runCurrent()
        assertTrue(a.sink.acks.isEmpty())
    }

    @Test fun futureTimestampsAreClamped() = runTest {
        val (a, b) = line("A", "B")
        a.boot(); b.boot()
        runCurrent()
        val farFuture = 10L * 24 * 60 * 60_000
        val m = ChatMessage("skewed", a.id, "A", farFuture, "from the future")
        a.engine.originate(ObjectType.MESSAGE, m.id, m.timestampMs, WireCodec.encodePayload(m))
        runCurrent()
        assertTrue(b.sink.messages.single().m.timestampMs <= testScheduler.currentTime + 24L * 60 * 60_000)
    }

    @Test fun unknownTypesAreStoredAndRelayedUntouched() = runTest {
        val (a, b, c) = line("A", "B", "C")
        listOf(a, b, c).forEach { it.boot() }
        runCurrent()
        a.engine.originate(ObjectType.PIN, "pin-1", 5, byteArrayOf(1, 2, 3))
        runCurrent()
        assertEquals(listOf<Byte>(1, 2, 3), c.store.map["pin-1"]!!.payload.toList())
        assertNull(c.store.map["pin-1"]?.takeIf { it.outTtl > 1 })
    }
}
