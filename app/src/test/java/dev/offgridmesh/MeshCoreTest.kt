package dev.offgridmesh

import dev.offgridmesh.mesh.MeshConfig
import dev.offgridmesh.mesh.MeshCore
import dev.offgridmesh.transport.PeerInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshCoreTest {
    private val idLow = "00000000-0000-0000-0000-000000000001"
    private val idHigh = "ffffffff-0000-0000-0000-000000000002"

    private class Device(val core: MeshCore, val radio: FakeRadio, val nodes: FakeNodes)

    private fun TestScope.device(air: FakeAir, endpoint: String): Device {
        val radio = FakeRadio(air, endpoint)
        val nodes = FakeNodes()
        val core = MeshCore(radio, nodes, backgroundScope, clock = { testScheduler.currentTime })
        return Device(core, radio, nodes)
    }

    @Test fun twoDevicesDiscoverAndConnect() = runTest {
        val air = FakeAir()
        val a = device(air, "A")
        val b = device(air, "B")
        a.core.start(PeerInfo(idLow, "Alpha"))
        b.core.start(PeerInfo(idHigh, "Bravo"))
        runCurrent()

        assertEquals(setOf(idHigh), a.core.state.value.connectedDeviceIds)
        assertEquals(setOf(idLow), b.core.state.value.connectedDeviceIds)
        assertEquals("only the lower id initiates", 1, air.requests)
        assertEquals("Bravo", a.nodes.seen[idHigh]!!.first)
        assertEquals("Alpha", b.nodes.seen[idLow]!!.first)
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
