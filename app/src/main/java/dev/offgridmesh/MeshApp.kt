package dev.offgridmesh

import android.app.Application
import dev.offgridmesh.data.ChatRepository
import dev.offgridmesh.data.IdentityStore
import dev.offgridmesh.data.MeshDatabase
import dev.offgridmesh.data.SettingsStore
import dev.offgridmesh.mesh.MeshState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MeshApp : Application() {
    val db by lazy { MeshDatabase.create(this) }
    val identity by lazy { IdentityStore(this) }
    val settings by lazy { SettingsStore(this) }

    /** Ids of locally created objects for the running SyncEngine to push now (missed ones go by inventory). */
    val outbox = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val chat by lazy { ChatRepository(db, identity, settings, outbox) }

    /** Set while the chat screen is on screen, so incoming messages don't also raise a notification. */
    @Volatile var chatVisible = false

    /** Published by MeshService; the UI reads it and never touches the transport (§4). */
    internal val meshStateFlow = MutableStateFlow(MeshState())
    val meshState: StateFlow<MeshState> = meshStateFlow.asStateFlow()
}
