package dev.offgridmesh

import dev.offgridmesh.mesh.MeshConfig
import dev.offgridmesh.mesh.MeshCore
import dev.offgridmesh.mesh.NodeSink
import dev.offgridmesh.transport.MeshTransport
import dev.offgridmesh.transport.PeerInfo
import dev.offgridmesh.transport.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A simulated air: radios in range see each other, connections go through only when requested and the
 * other radio is up. Stands in for the physical-device rig on the JVM — it checks MeshCore's policy,
 * not Nearby's behavior.
 */
private class FakeAir {
    val radios = mutableListOf<FakeRadio>()
    val links = mutableSetOf<Pair<FakeRadio, FakeRadio>>()
    var requests = 0

    fun inRange(a: FakeRadio, b: FakeRadio) = a.up && b.up && (a.reachable == null || b in a.reachable!!)

    fun announce() {
        for (a in radios) for (b in radios) if (a !== b && inRange(a, b)) a.found(b)
    }
}

private class FakeRadio(val air: FakeAir, val endpointName: String) : MeshTransport {
    private val ch = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = ch.receiveAsFlow()
    var self: PeerInfo? = null
    var up = false
    var reachable: Set<FakeRadio>? = null
    val known = mutableSetOf<String>()
    val sent = mutableListOf<Pair<String, ByteArray>>()

    init { air.radios += this }

    fun found(other: FakeRadio) {
        if (known.add(other.endpointName)) ch.trySend(TransportEvent.Discovered(other.endpointName, other.self))
    }

    private fun peer(name: String) = air.radios.first { it.endpointName == name }

    override fun start(self: PeerInfo) { this.self = self; up = true; air.announce() }

    override fun stop() {
        up = false
        known.clear()
        air.links.filter { it.first === this || it.second === this }.forEach { link ->
            air.links -= link
            val other = if (link.first === this) link.second else link.first
            other.ch.trySend(TransportEvent.Disconnected(endpointName))
            other.known -= endpointName
        }
    }

    override fun refreshDiscovery() { known.clear(); air.announce() }

    override fun connect(endpointId: String) {
        air.requests++
        val other = peer(endpointId)
        if (!air.inRange(this, other) || air.links.any { it == this to other || it == other to this }) {
            ch.trySend(TransportEvent.ConnectFailed(endpointId, "unreachable or already connected"))
            return
        }
        air.links += this to other
        ch.trySend(TransportEvent.Connected(endpointId, other.self))
        other.ch.trySend(TransportEvent.Connected(endpointName, self))
    }

    override fun send(endpointId: String, bytes: ByteArray) {
        sent += endpointId to bytes
        val other = peer(endpointId)
        if (air.links.any { it == this to other || it == other to this }) {
            other.ch.trySend(TransportEvent.Received(endpointName, bytes))
        }
    }
}

private class FakeNodes : NodeSink {
    val seen = mutableMapOf<String, Triple<String, Long, Int?>>()
    override suspend fun recordContact(deviceId: String, displayName: String, seenAtMs: Long, hopCount: Int, batteryPct: Int?) {
        seen[deviceId] = Triple(displayName, seenAtMs, batteryPct ?: seen[deviceId]?.third)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MeshCoreTest {
    private val idLow = "00000000-0000-0000-0000-000000000001"
    private val idHigh = "ffffffff-0000-0000-0000-000000000002"

    private class Device(val core: MeshCore, val radio: FakeRadio, val nodes: FakeNodes)

    private fun TestScope.device(air: FakeAir, endpoint: String, battery: Int? = null): Device {
        val radio = FakeRadio(air, endpoint)
        val nodes = FakeNodes()
        val core = MeshCore(radio, nodes, backgroundScope, clock = { testScheduler.currentTime }, batteryPct = { battery })
        return Device(core, radio, nodes)
    }

    @Test fun twoDevicesDiscoverConnectAndExchangePresence() = runTest {
        val air = FakeAir()
        val a = device(air, "A", battery = 80)
        val b = device(air, "B", battery = 40)
        a.core.start(PeerInfo(idLow, "Alpha"))
        b.core.start(PeerInfo(idHigh, "Bravo"))
        runCurrent()

        assertEquals(setOf(idHigh), a.core.state.value.connectedDeviceIds)
        assertEquals(setOf(idLow), b.core.state.value.connectedDeviceIds)
        assertEquals("only the lower id initiates", 1, air.requests)
        assertEquals(Triple("Bravo", 0L, 40), a.nodes.seen[idHigh])
        assertEquals(Triple("Alpha", 0L, 80), b.nodes.seen[idLow])
    }

    @Test fun higherIdFallsBackWhenLowerCannotSeeIt() = runTest {
        val air = FakeAir()
        val a = device(air, "A")
        val b = device(air, "B")
        a.radio.reachable = emptySet() // asymmetric discovery: A never hears B's adverts
        b.radio.reachable = setOf(a.radio)
        a.core.start(PeerInfo(idLow, "Alpha"))
        b.core.start(PeerInfo(idHigh, "Bravo"))
        runCurrent()
        assertTrue(b.core.state.value.connectedDeviceIds.isEmpty())

        a.radio.reachable = null // the link itself works; only A's discovery was deaf
        advanceTimeBy(MeshConfig.CONNECT_FALLBACK_MS + MeshConfig.MAINTENANCE_TICK_MS)
        runCurrent()
        assertEquals(setOf(idLow), b.core.state.value.connectedDeviceIds)
    }

    @Test fun stopTearsDownOnBothSidesAndRestartReconnects() = runTest {
        val air = FakeAir()
        val a = device(air, "A")
        val b = device(air, "B")
        a.core.start(PeerInfo(idLow, "Alpha"))
        b.core.start(PeerInfo(idHigh, "Bravo"))
        runCurrent()

        b.core.stop()
        runCurrent()
        assertFalse(b.core.state.value.running)
        assertTrue(b.core.state.value.peers.isEmpty())
        assertTrue("A drops B on disconnect", a.core.state.value.peers.isEmpty())

        b.core.start(PeerInfo(idHigh, "Bravo"))
        runCurrent()
        assertEquals(setOf(idHigh), a.core.state.value.connectedDeviceIds)
    }

    @Test fun presenceBeaconsRepeatAndRefreshLastSeen() = runTest {
        val air = FakeAir()
        val a = device(air, "A")
        val b = device(air, "B")
        a.core.start(PeerInfo(idLow, "Alpha"))
        b.core.start(PeerInfo(idHigh, "Bravo"))
        runCurrent()
        advanceTimeBy(MeshConfig.PRESENCE_INTERVAL_MS * 2)
        runCurrent()
        assertEquals(MeshConfig.PRESENCE_INTERVAL_MS * 2, a.nodes.seen[idHigh]!!.second)
    }

    @Test fun renamePropagatesImmediately() = runTest {
        val air = FakeAir()
        val a = device(air, "A")
        val b = device(air, "B")
        a.core.start(PeerInfo(idLow, "Alpha"))
        b.core.start(PeerInfo(idHigh, "Bravo"))
        runCurrent()
        b.core.rename("Bravo 2")
        runCurrent()
        assertEquals("Bravo 2", a.nodes.seen[idHigh]!!.first)
    }

    @Test fun foreignAdvertsAndGarbagePayloadsAreIgnored() = runTest {
        val air = FakeAir()
        val a = device(air, "A")
        val stranger = FakeRadio(air, "X") // self stays null → undecodable endpoint info
        a.core.start(PeerInfo(idLow, "Alpha"))
        stranger.up = true
        air.announce()
        runCurrent()
        assertTrue(a.core.state.value.peers.isEmpty())
        assertEquals(0, air.requests)
    }
}
