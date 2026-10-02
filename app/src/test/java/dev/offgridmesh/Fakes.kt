package dev.offgridmesh

import dev.offgridmesh.mesh.NodeSink
import dev.offgridmesh.transport.MeshTransport
import dev.offgridmesh.transport.PeerInfo
import dev.offgridmesh.transport.TransportEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A simulated air: radios in range see each other, connections go through only when requested and the
 * other radio is up. Stands in for the physical-device rig on the JVM — it checks MeshCore's policy,
 * not Nearby's behavior.
 */
internal class FakeAir {
    val radios = mutableListOf<FakeRadio>()
    val links = mutableSetOf<Pair<FakeRadio, FakeRadio>>()
    var requests = 0

    fun inRange(a: FakeRadio, b: FakeRadio) = a.up && b.up && (a.reachable == null || b in a.reachable!!)

    fun announce() {
        for (a in radios) for (b in radios) if (a !== b && inRange(a, b)) a.found(b)
    }
}

internal class FakeRadio(val air: FakeAir, val endpointName: String) : MeshTransport {
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

internal class FakeNodes : NodeSink {
    val seen = mutableMapOf<String, Triple<String, Long, Int?>>()
    override suspend fun recordContact(deviceId: String, displayName: String, seenAtMs: Long, hopCount: Int, batteryPct: Int?) {
        seen[deviceId] = Triple(displayName, seenAtMs, batteryPct ?: seen[deviceId]?.third)
    }
}

