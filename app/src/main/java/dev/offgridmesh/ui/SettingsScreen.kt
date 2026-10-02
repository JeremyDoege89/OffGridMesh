package dev.offgridmesh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offgridmesh.BuildConfig
import dev.offgridmesh.MeshApp
import dev.offgridmesh.data.IdentityStore
import dev.offgridmesh.mesh.MeshConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Settings (§7.8). Packs, export etc. land with their milestones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onPreTripSetup: () -> Unit) {
    BackHandler(onBack = onBack)
    val app = LocalContext.current.applicationContext as MeshApp
    val scope = rememberCoroutineScope()
    val identity by app.identity.identity.collectAsStateWithLifecycle(initialValue = null)
    val saved = identity?.displayName ?: return
    var name by rememberSaveable(saved) { mutableStateOf(saved) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(IdentityStore.MAX_NAME) },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { scope.launch { app.identity.setDisplayName(name) } },
                enabled = name.isNotBlank() && name.trim() != saved,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save name") }

            HorizontalDivider()
            val ttl by app.settings.ttlHops.collectAsStateWithLifecycle(initialValue = MeshConfig.DEFAULT_TTL_HOPS)
            Text("Relay hops (TTL): $ttl", style = MaterialTheme.typography.titleMedium)
            Text(
                "How many phone-to-phone hops your messages may travel. More hops reach further but cost every " +
                    "relay battery. Default ${MeshConfig.DEFAULT_TTL_HOPS}.",
            )
            Slider(
                value = ttl.toFloat(),
                onValueChange = { v -> scope.launch { app.settings.setTtlHops(v.roundToInt()) } },
                valueRange = MeshConfig.TTL_RANGE.first.toFloat()..MeshConfig.TTL_RANGE.last.toFloat(),
                steps = MeshConfig.TTL_RANGE.last - MeshConfig.TTL_RANGE.first - 1,
            )

            HorizontalDivider()
            OutlinedButton(onClick = onPreTripSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Pre-trip setup")
            }

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Mesh transport: Google Nearby Connections — requires Google Play Services.")
            Text(
                "Everything stays on your team's phones. There is no server and no account; the developer can't see " +
                    "your messages, pins or locations because there is nowhere for them to go.",
            )
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
