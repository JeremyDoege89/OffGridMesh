package dev.offgridmesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.offgridmesh.mesh.MeshService
import dev.offgridmesh.ui.AppRoot
import dev.offgridmesh.ui.OffGridTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeshService.createChannel(this)
        setContent { OffGridTheme { AppRoot() } }
    }
}
