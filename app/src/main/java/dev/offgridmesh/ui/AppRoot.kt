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
import dev.offgridmesh.ui.map.DownloadAreaScreen
import dev.offgridmesh.ui.map.MapPacksScreen
import dev.offgridmesh.ui.map.MapScreen

enum class Tab(val label: String, val icon: ImageVector) {
    MESH("Mesh", Icons.Filled.Hub),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    MAP("Map", Icons.Filled.Map),
    TASKS("Tasks", Icons.Filled.Checklist),
    STATUS("Status", Icons.Filled.HealthAndSafety),
}

enum class Page { SETTINGS, PACKS, DOWNLOAD }

@Composable
fun AppRoot(requestedTab: Tab? = null, onTabConsumed: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as MeshApp
    LaunchedEffect(Unit) { app.identity.ensureDeviceId() }
    val identity by app.identity.identity.collectAsStateWithLifecycle(initialValue = null)
    val id = identity ?: return
    if (id.deviceId.isEmpty()) return

    var rerunSetup by rememberSaveable { mutableStateOf(false) }
    // Full-screen pages over the tabs; each Back returns to the page it came from.
    var stack by rememberSaveable { mutableStateOf(listOf<Page>()) }
    val push = { p: Page -> stack = stack + p }
    val pop = { stack = stack.dropLast(1) }

    when (stack.lastOrNull()) {
        Page.DOWNLOAD -> { DownloadAreaScreen(onBack = pop); return }
        Page.PACKS -> { MapPacksScreen(onBack = pop, onDownload = { push(Page.DOWNLOAD) }); return }
        Page.SETTINGS -> {
            SettingsScreen(
                onBack = pop,
                onPreTripSetup = { stack = emptyList(); rerunSetup = true },
                onOpenPacks = { push(Page.PACKS) },
            )
            return
        }
        null -> Unit
    }

    if (!id.onboarded || rerunSetup) {
        OnboardingScreen(
            initialName = id.displayName,
            onDownloadMap = { push(Page.DOWNLOAD) },
            onDone = { rerunSetup = false },
        )
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.MESH) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            stack = emptyList()
            onTabConsumed()
        }
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
                Tab.MESH -> MeshScreen(onOpenSettings = { push(Page.SETTINGS) })
                Tab.CHAT -> ChatScreen()
                Tab.MAP -> MapScreen(onOpenPacks = { push(Page.PACKS) })
                Tab.TASKS -> ComingSoon("Tasks", "Shared checklists arrive in milestone M4.")
                Tab.STATUS -> ComingSoon("Status", "The OK / Need help board arrives in milestone M4.")
            }
        }
    }
}
