package dev.offgridmesh.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.offgridmesh.mesh.MeshConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Nearby Connections (Play Services) — the MVP transport decided in Build Guide §3.2.
 * P2P_CLUSTER gives M-to-N connections over BLE / Bluetooth Classic / Wi-Fi as Play Services sees fit;
 * multi-hop relay is the app's job, done above this layer.
 */
class NearbyTransport(context: Context) : MeshTransport {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = Strategy.P2P_CLUSTER
    private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = channel.receiveAsFlow()

    /** Peer info from onConnectionInitiated, held until onConnectionResult reports the outcome. */
    private val initiated = ConcurrentHashMap<String, PeerInfo?>()
    private var self: PeerInfo? = null

    private fun emit(e: TransportEvent) {
        channel.trySend(e)
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return // MVP sends BYTES only (§4: no chunking, no files)
            emit(TransportEvent.Received(endpointId, bytes))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            initiated[endpointId] = PeerInfoCodec.decode(info.endpointInfo)
            // M4 replaces blind acceptance with the team-passphrase check (§10).
            client.acceptConnection(endpointId, payloads)
                .addOnFailureListener { emit(TransportEvent.ConnectFailed(endpointId, "accept: ${it.message}")) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val info = initiated.remove(endpointId)
            if (resolution.status.isSuccess) {
                emit(TransportEvent.Connected(endpointId, info))
            } else {
                emit(TransportEvent.ConnectFailed(endpointId, "status ${resolution.status.statusCode}"))
            }
        }

        override fun onDisconnected(endpointId: String) {
            emit(TransportEvent.Disconnected(endpointId))
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != MeshConfig.SERVICE_ID) return
            emit(TransportEvent.Discovered(endpointId, PeerInfoCodec.decode(info.endpointInfo)))
        }

        override fun onEndpointLost(endpointId: String) {
            emit(TransportEvent.Lost(endpointId))
        }
    }

    override fun start(self: PeerInfo) {
        this.self = self
        client.startAdvertising(
            PeerInfoCodec.encode(self), MeshConfig.SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { Log.w(TAG, "startAdvertising failed", it) }
        startDiscovery()
    }

    private fun startDiscovery() {
        client.startDiscovery(
            MeshConfig.SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { Log.w(TAG, "startDiscovery failed", it) }
    }

    override fun refreshDiscovery() {
        if (self == null) return
        client.stopDiscovery()
        startDiscovery()
    }

    override fun stop() {
        self = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        initiated.clear()
    }

    override fun connect(endpointId: String) {
        val me = self ?: return
        client.requestConnection(PeerInfoCodec.encode(me), endpointId, lifecycle)
            .addOnFailureListener { emit(TransportEvent.ConnectFailed(endpointId, "request: ${it.message}")) }
    }

    override fun send(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { Log.w(TAG, "send to $endpointId failed", it) }
    }

    private companion object {
        const val TAG = "NearbyTransport"
    }
}
