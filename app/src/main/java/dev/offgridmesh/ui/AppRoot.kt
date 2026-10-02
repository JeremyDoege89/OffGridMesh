package dev.offgridmesh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offgridmesh.MeshApp

enum class Tab(val label: String, val icon: ImageVector) {
    MESH("Mesh", Icons.Filled.Hub),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    MAP("Map", Icons.Filled.Map),
    TASKS("Tasks", Icons.Filled.Checklist),
    STATUS("Status", Icons.Filled.HealthAndSafety),
}

@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as MeshApp
    LaunchedEffect(Unit) { app.identity.ensureDeviceId() }
    val identity by app.identity.identity.collectAsStateWithLifecycle(initialValue = null)
    val id = identity ?: return
    if (id.deviceId.isEmpty()) return

    var rerunSetup by rememberSaveable { mutableStateOf(false) }
    if (!id.onboarded || rerunSetup) {
        OnboardingScreen(initialName = id.displayName, onDone = { rerunSetup = false })
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.MESH) }
    var settings by rememberSaveable { mutableStateOf(false) }
    if (settings) {
        SettingsScreen(onBack = { settings = false }, onPreTripSetup = { settings = false; rerunSetup = true })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) }, // labels always visible: no icon-only nav (§7)
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.MESH -> MeshScreen(onOpenSettings = { settings = true })
                Tab.CHAT -> ComingSoon("Chat", "Group chat with store-and-forward relay arrives in milestone M2.")
                Tab.MAP -> ComingSoon("Map", "Offline maps and pins arrive in milestone M3.")
                Tab.TASKS -> ComingSoon("Tasks", "Shared checklists arrive in milestone M4.")
                Tab.STATUS -> ComingSoon("Status", "The OK / Need help board arrives in milestone M4.")
            }
        }
    }
}
