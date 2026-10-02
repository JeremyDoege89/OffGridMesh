package dev.offgridmesh

import android.app.Application
import dev.offgridmesh.data.IdentityStore
import dev.offgridmesh.data.MeshDatabase
import dev.offgridmesh.mesh.MeshState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MeshApp : Application() {
    val db by lazy { MeshDatabase.create(this) }
    val identity by lazy { IdentityStore(this) }

    /** Published by MeshService; the UI reads it and never touches the transport (§4). */
    internal val meshStateFlow = MutableStateFlow(MeshState())
    val meshState: StateFlow<MeshState> = meshStateFlow.asStateFlow()
}
