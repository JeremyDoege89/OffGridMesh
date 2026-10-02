package dev.offgridmesh.transport

import kotlinx.coroutines.flow.Flow

/**
 * The radio boundary (Build Guide §4). MVP has one implementation, [NearbyTransport]; a v2 DIY BLE
 * transport implements the same contract so SyncEngine and everything above it stay untouched.
 *
 * Policy (who connects to whom, what gets sent) lives in MeshCore, not here: a transport only moves
 * bytes between endpoints and reports what happened. Incoming connections are accepted automatically.
 */
interface MeshTransport {
    /** Single-consumer stream of everything the radio reports. */
    val events: Flow<TransportEvent>

    /** Advertise [self] and discover peers until [stop]. */
    fun start(self: PeerInfo)

    /** Stop advertising/discovery and drop every connection. */
    fun stop()

    /** Restart discovery so endpoints still in range are reported again (used after a disconnect). */
    fun refreshDiscovery()

    fun connect(endpointId: String)

    fun disconnect(endpointId: String)

    fun send(endpointId: String, bytes: ByteArray)
}

sealed interface TransportEvent {
    val endpointId: String

    /** [info] is null when the advertisement isn't ours or is from an incompatible version. */
    data class Discovered(override val endpointId: String, val info: PeerInfo?) : TransportEvent
    data class Lost(override val endpointId: String) : TransportEvent
    data class Connected(override val endpointId: String, val info: PeerInfo?) : TransportEvent
    data class ConnectFailed(override val endpointId: String, val reason: String) : TransportEvent
    data class Disconnected(override val endpointId: String) : TransportEvent
    class Received(override val endpointId: String, val bytes: ByteArray) : TransportEvent
}
