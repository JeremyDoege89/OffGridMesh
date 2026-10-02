package dev.offgridmesh.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offgridmesh.MeshApp
import dev.offgridmesh.data.MapPack
import dev.offgridmesh.billing.Entitlements
import dev.offgridmesh.billing.ProFeature
import kotlinx.coroutines.launch

/** Downloaded packs with size, zoom range and per-pack delete (§6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPacksScreen(onBack: () -> Unit, onDownload: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext as MeshApp
    val scope = rememberCoroutineScope()
    val packs by app.mapPacks.packs.collectAsStateWithLifecycle(initialValue = emptyList())
    var deleting by remember { mutableStateOf<MapPack?>(null) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text("Map packs") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text(
                    "Maps you download here work with no signal. Download them before you go — you can't in the field.",
                    Modifier.padding(vertical = 8.dp),
                )
            }
            if (packs.isEmpty()) item { Text("No map packs yet.", style = MaterialTheme.typography.titleMedium) }
            items(packs, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Text(
                            "${formatBytes(p.sizeBytes)} · zoom ${p.minZoom}–${p.maxZoom}\n" +
                                "%.3f, %.3f to %.3f, %.3f".format(p.south, p.west, p.north, p.east),
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${p.name}") }
                    },
                )
            }
            item {
                val free = context.filesDir.usableSpace
                Text("Free space on this phone: ${formatBytes(free)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        val pro by app.pro.status.collectAsStateWithLifecycle()
        Button(onClick = {
            if (Entitlements.canAddMapPack(pro.state, packs.size)) onDownload() else app.requestPaywall(ProFeature.MAP_PACKS)
        }, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp)) {
            Text("Download a map area")
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("You'll need internet to download it again.") },
            confirmButton = {
                Button(onClick = { scope.launch { app.mapPacks.delete(p) }; deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}
