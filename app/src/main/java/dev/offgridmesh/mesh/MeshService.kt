package dev.offgridmesh.mesh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.offgridmesh.MainActivity
import dev.offgridmesh.MeshApp
import dev.offgridmesh.R
import dev.offgridmesh.transport.NearbyTransport
import dev.offgridmesh.transport.PeerInfo
import dev.offgridmesh.data.RoomGossipStore
import dev.offgridmesh.data.RoomSyncSink
import dev.offgridmesh.sync.ChatMessage
import dev.offgridmesh.sync.SyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The only thing that starts or stops radios (§4). Runs only while the user has Mesh switched on, as a
 * foreground service whose notification is the user's proof the mesh is alive (§3.5, §9).
 */
class MeshService : LifecycleService() {
    private val app get() = application as MeshApp
    private var core: MeshCore? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (core != null) return START_NOT_STICKY
        if (!MeshPermissions.canRunMesh(this)) {
            Log.w(TAG, "mesh permissions missing; not starting")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(0),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: RuntimeException) {
            // SecurityException / ForegroundServiceStartNotAllowedException: say so, don't crash the app.
            Log.w(TAG, "startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val newCore = MeshCore(
            transport = NearbyTransport(this),
            nodes = NodeSink(app.db.nodes()::recordContact),
            scope = lifecycleScope,
        )
        core = newCore
        lifecycleScope.launch { newCore.state.collect { app.meshStateFlow.value = it } }
        lifecycleScope.launch {
            newCore.state.map { it.connectedCount }.distinctUntilChanged().collect { n ->
                if (canNotify()) NotificationManagerCompat.from(this@MeshService).notify(NOTIFICATION_ID, notification(n))
            }
        }
        lifecycleScope.launch {
            val me = app.identity.current()
            var ttl = app.settings.ttlHops.first()
            launch { app.settings.ttlHops.collect { ttl = it } }
            app.db.gossip().prune(System.currentTimeMillis() - MeshConfig.GOSSIP_RETENTION_MS)

            val engine = SyncEngine(
                selfId = me.deviceId,
                store = RoomGossipStore(app.db.gossip()),
                sink = RoomSyncSink(app.db, me.deviceId, ::notifyMessage),
                link = newCore,
                ttlHops = { ttl },
            )
            newCore.listener = engine
            newCore.start(PeerInfo(me.deviceId, me.displayName.ifBlank { "Unnamed" }))

            launch { app.outbox.collect { engine.push(it) } }
            launch {
                // Presence beacon now and every interval, and right away on a rename.
                app.identity.identity.map { it.displayName }.distinctUntilChanged().collectLatest { name ->
                    newCore.rename(name)
                    while (true) {
                        engine.originatePresence(name.ifBlank { "Unnamed" }, batteryPct())
                        delay(MeshConfig.PRESENCE_INTERVAL_MS)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        core?.stop()
        core = null
        app.meshStateFlow.value = MeshState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        core?.stop()
        core = null
        app.meshStateFlow.value = MeshState()
        super.onDestroy()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun notifyMessage(m: ChatMessage) {
        if (app.chatVisible || !canNotify()) return
        val n = NotificationCompat.Builder(this, MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(m.senderName.ifBlank { "Unnamed" })
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 2, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "CHAT"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        NotificationManagerCompat.from(this).notify(MESSAGE_NOTIFICATION_ID, n)
    }

    private fun batteryPct(): Int? =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

    private fun notification(nearby: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mesh)
        .setContentTitle(getString(R.string.notif_title, nearby))
        .setContentText(getString(R.string.notif_text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0, getString(R.string.notif_stop),
            PendingIntent.getService(
                this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 1
        private const val MESSAGES_CHANNEL_ID = "messages"
        private const val MESSAGE_NOTIFICATION_ID = 2
        private const val ACTION_STOP = "dev.offgridmesh.STOP"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.channel_mesh), NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_mesh_desc) }
            val messages = NotificationChannel(
                MESSAGES_CHANNEL_ID, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, messages))
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeshService::class.java).setAction(ACTION_STOP))
        }
    }
}
