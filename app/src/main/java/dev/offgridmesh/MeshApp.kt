package dev.offgridmesh

import android.app.Application
import dev.offgridmesh.billing.BillingGateway
import dev.offgridmesh.billing.DataStoreEntitlementCache
import dev.offgridmesh.billing.FakeBillingGateway
import dev.offgridmesh.billing.PlayBillingGateway
import dev.offgridmesh.billing.ProFeature
import dev.offgridmesh.billing.ProManager
import dev.offgridmesh.ui.PaywallRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.offgridmesh.data.ChatRepository
import dev.offgridmesh.data.IdentityStore
import dev.offgridmesh.data.MeshDatabase
import dev.offgridmesh.data.PinRepository
import dev.offgridmesh.data.LocalWriter
import dev.offgridmesh.data.ChecklistRepository
import dev.offgridmesh.data.StatusRepository
import dev.offgridmesh.maps.MapPackManager
import dev.offgridmesh.crypto.TeamStore
import dev.offgridmesh.data.SettingsStore
import dev.offgridmesh.mesh.MeshState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MeshApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val billing: BillingGateway by lazy {
        if (BuildConfig.DEBUG && FakeBillingGateway.enabled(this)) FakeBillingGateway(this) else PlayBillingGateway(this)
    }
    val pro by lazy { ProManager(billing, DataStoreEntitlementCache(this), appScope) }

    /** The open paywall, if any; any screen may ask (UI-layer gates only, §11). */
    val paywall = MutableStateFlow<PaywallRequest?>(null)

    /** The automatic 9th-node offer is made at most once per run. */
    @Volatile var nodeCapOffered = false

    fun requestPaywall(feature: ProFeature?) {
        paywall.value = PaywallRequest(feature)
    }

    override fun onCreate() {
        super.onCreate()
        pro.start()
    }

    val db by lazy { MeshDatabase.create(this) }
    val identity by lazy { IdentityStore(this) }
    val settings by lazy { SettingsStore(this) }
    val team by lazy { TeamStore(this) }

    /** Ids of locally created objects for the running SyncEngine to push now (missed ones go by inventory). */
    val outbox = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val chat by lazy { ChatRepository(db, identity, settings, outbox) }
    val pins by lazy { PinRepository(db, identity, settings, outbox) }
    private val writer by lazy { LocalWriter(db, identity, settings, outbox) }
    val checklists by lazy { ChecklistRepository(db, writer) }
    val status by lazy { StatusRepository(db, writer) }
    val mapPacks by lazy { MapPackManager(this, db.mapPacks()) }

    /** Set while the chat screen is on screen, so incoming messages don't also raise a notification. */
    @Volatile var chatVisible = false

    /** Published by MeshService; the UI reads it and never touches the transport (§4). */
    internal val meshStateFlow = MutableStateFlow(MeshState())
    val meshState: StateFlow<MeshState> = meshStateFlow.asStateFlow()
}
