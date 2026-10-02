package dev.offgridmesh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.offgridmesh.mesh.MeshService
import dev.offgridmesh.ui.AppRoot
import dev.offgridmesh.ui.OffGridTheme
import dev.offgridmesh.ui.Tab

class MainActivity : ComponentActivity() {
    private var requestedTab by mutableStateOf<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeshService.createChannel(this)
        if (savedInstanceState == null) requestedTab = tabFrom(intent)
        setContent { OffGridTheme { AppRoot(requestedTab, onTabConsumed = { requestedTab = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab = tabFrom(intent)
    }

    private fun tabFrom(intent: Intent?) = intent?.getStringExtra(EXTRA_TAB)?.let { name ->
        Tab.entries.firstOrNull { it.name == name }
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
