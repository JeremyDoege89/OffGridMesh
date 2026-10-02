package dev.offgridmesh.mesh

import dev.offgridmesh.sync.PeerLink
import dev.offgridmesh.transport.MeshTransport
import dev.offgridmesh.transport.PeerInfo
import dev.offgridmesh.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One radio endpoint as MeshCore tracks it. Endpoint ids are per-session; deviceId is the stable identity. */
data class Peer(
    val endpointId: String,
    val info: PeerInfo?,
    val discoveredAtMs: Long,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val lastAttemptMs: Long = 0L,
)

data class MeshState(
    val running: Boolean = false,
    val peers: Map<String, Peer> = emptyMap(),
    /** endpointId → deviceId of app phones nearby on a different team passphrase (or older build): never joined. */
    val foreign: Map<String, String> = emptyMap(),
    val encrypted: Boolean = false,
) {
    val connectedDeviceIds: Set<String>
        get() = peers.values.filter { it.connected }.mapNotNullTo(mutableSetOf()) { it.info?.deviceId }
    val connectedCount: Int get() = connectedDeviceIds.size
    val foreignNearby: Int get() = foreign.values.toSet().size
}

/** Where MeshCore records who it has heard from (the Node table in the app, a fake in tests). */
fun interface NodeSink {
    suspend fun recordContact(deviceId: String, displayName: String, seenAtMs: Long, hopCount: Int, batteryPct: Int?)
}

/** What sits above the connection layer: SyncEngine in the app. */
interface MeshListener {
    suspend fun onConnected(endpointId: String)
    suspend fun onReceived(endpointId: String, bytes: ByteArray)
}

/**
 * Transport-agnostic connection manager: decides who to connect to and keeps the endpoint set. Payloads
 * go to [listener]; MeshService owns one per mesh session.
 *
 * Confined to the single thread of [scope] (the service's main-thread scope; the test dispatcher in
 * tests) — state is read-modify-written without locks on that assumption.
 */
class MeshCore(
    private val transport: MeshTransport,
    private val nodes: NodeSink,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : PeerLink {
    var listener: MeshListener? = null

    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private var self: PeerInfo? = null
    private var jobs: List<Job> = emptyList()

    private val peers get() = _state.value.peers

    /** [self].teamTag decides who we join; [encrypted] is shown in the UI's padlock. */
    fun start(self: PeerInfo, encrypted: Boolean = false) {
        if (_state.value.running) return
        this.self = self
        _state.value = MeshState(running = true, encrypted = encrypted)
        jobs = listOf(
            scope.launch { transport.events.collect { handle(it) } },
            scope.launch {
                while (isActive) {
                    delay(MeshConfig.MAINTENANCE_TICK_MS)
                    maintain()
                }
            },
        )
        transport.start(self)
    }

    fun stop() {
        if (!_state.value.running) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        transport.stop()
        _state.value = MeshState(running = false)
    }

    private fun sameTeam(info: PeerInfo?) = info != null && info.teamTag != null && info.teamTag == self?.teamTag

    private fun setForeign(endpointId: String, deviceId: String?) {
        val s = _state.value
        val next = if (deviceId != null) s.foreign + (endpointId to deviceId) else s.foreign - endpointId
        if (next != s.foreign) _state.value = s.copy(foreign = next)
    }

    /** Name used in connection requests from now on (adverts pick it up next session). */
    fun rename(displayName: String) {
        self = self?.copy(displayName = displayName)
    }

    override fun connectedPeers(): Map<String, String?> =
        peers.values.filter { it.connected }.associate { it.endpointId to it.info?.deviceId }

    override fun send(endpointId: String, bytes: ByteArray) {
        if (peers[endpointId]?.connected == true) transport.send(endpointId, bytes)
    }

    private fun put(p: Peer) {
        _state.value = _state.value.copy(peers = peers + (p.endpointId to p))
    }

    private fun remove(endpointId: String) {
        _state.value = _state.value.copy(peers = peers - endpointId)
    }

    internal suspend fun handle(e: TransportEvent) {
        if (!_state.value.running) return
        val now = clock()
        when (e) {
            is TransportEvent.Discovered -> {
                val info = e.info ?: return // not ours or incompatible version: ignore, never connect
                if (info.deviceId == self?.deviceId) return
                if (!sameTeam(info)) {
                    // Another team (or an older build): count it so the UI can say so, but never join it.
                    setForeign(e.endpointId, info.deviceId)
                    return
                }
                setForeign(e.endpointId, null)
                put(peers[e.endpointId]?.copy(info = info) ?: Peer(e.endpointId, info, discoveredAtMs = now))
                nodes.recordContact(info.deviceId, info.displayName, now, hopCount = 0, batteryPct = null)
                maybeConnect(e.endpointId, now)
            }

            is TransportEvent.Lost -> {
                setForeign(e.endpointId, null)
                val p = peers[e.endpointId] ?: return
                if (!p.connected && !p.connecting) remove(e.endpointId)
            }

            is TransportEvent.Connected -> {
                val p = peers[e.endpointId] ?: Peer(e.endpointId, e.info, discoveredAtMs = now)
                val info = p.info ?: e.info
                if (!sameTeam(e.info ?: info)) {
                    // An inbound connection from another team: drop it; nothing is exchanged.
                    (e.info ?: info)?.let { setForeign(e.endpointId, it.deviceId) }
                    remove(e.endpointId)
                    transport.disconnect(e.endpointId)
                    return
                }
                put(p.copy(info = info, connected = true, connecting = false))
                info?.let { nodes.recordContact(it.deviceId, it.displayName, now, hopCount = 0, batteryPct = null) }
                listener?.onConnected(e.endpointId)
            }

            is TransportEvent.ConnectFailed -> {
                val p = peers[e.endpointId] ?: return
                put(p.copy(connecting = false))
            }

            is TransportEvent.Disconnected -> {
                // Endpoint ids don't survive a reconnect reliably; forget it and let fresh discovery
                // report the peer again if it is still in range.
                if (e.endpointId in peers) {
                    remove(e.endpointId)
                    transport.refreshDiscovery()
                }
            }

            is TransportEvent.Received -> listener?.onReceived(e.endpointId, e.bytes)
        }
    }

    internal fun maintain() {
        val now = clock()
        for (p in peers.values) {
            if (p.connecting && now - p.lastAttemptMs >= MeshConfig.CONNECT_TIMEOUT_MS) {
                put(p.copy(connecting = false))
            }
            maybeConnect(p.endpointId, now)
        }
    }

    private fun maybeConnect(endpointId: String, now: Long) {
        val p = peers[endpointId] ?: return
        val info = p.info ?: return
        val me = self ?: return
        if (p.connected || p.connecting) return
        if (p.lastAttemptMs != 0L && now - p.lastAttemptMs < MeshConfig.RETRY_BACKOFF_MS) return
        val initiate = me.deviceId < info.deviceId || now - p.discoveredAtMs >= MeshConfig.CONNECT_FALLBACK_MS
        if (!initiate) return
        put(p.copy(connecting = true, lastAttemptMs = now))
        transport.connect(endpointId)
    }
}
