package dev.offgridmesh.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offgridmesh.MeshApp
import dev.offgridmesh.data.IdentityStore
import dev.offgridmesh.mesh.MeshConfig
import dev.offgridmesh.mesh.PermissionGroup
import kotlinx.coroutines.launch

private sealed interface Step {
    data object Name : Step
    data class Permission(val group: PermissionGroup) : Step
    data object MapPack : Step
    data object CanCant : Step
}

/** Pre-trip setup (§7.1). Re-runnable from Settings; every step works on Wi-Fi before departure. */
@Composable
fun OnboardingScreen(initialName: String, onDownloadMap: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshApp
    val scope = rememberCoroutineScope()
    val steps = remember {
        buildList {
            add(Step.Name)
            PermissionGroup.entries.filter { it.applicable }.forEach { add(Step.Permission(it)) }
            add(Step.MapPack)
            add(Step.CanCant)
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val packs by app.mapPacks.packs.collectAsStateWithLifecycle(initialValue = null)
    val noMapAcknowledged by app.settings.noMapAcknowledged.collectAsStateWithLifecycle(initialValue = false)
    var confirmNoMap by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    val next = { if (index < steps.lastIndex) index++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { next() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pre-trip setup · ${index + 1} of ${steps.size}", style = MaterialTheme.typography.labelLarge)
        when (val step = steps[index]) {
            Step.Name -> {
                Heading("What should your team see?")
                Text("Your display name is shown to everyone on the mesh. No account, no phone number.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(IdentityStore.MAX_NAME) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                BigButton("Continue", enabled = name.isNotBlank()) {
                    scope.launch { app.identity.setDisplayName(name) }
                    next()
                }
            }

            is Step.Permission -> {
                val (title, body) = rationale(step.group)
                Heading(title)
                Text(body)
                if (step.group.granted(context)) {
                    Text("Already allowed.", fontWeight = FontWeight.Bold)
                    BigButton("Continue") { next() }
                } else {
                    BigButton("Allow") { launcher.launch(step.group.permissions.toTypedArray()) }
                    TextButton(onClick = { next() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") }
                }
            }

            Step.MapPack -> {
                // Forced but skippable (§6): the skip needs an explicit confirmation.
                Heading("Download your area map now")
                Text(
                    "Do it while you have Wi-Fi — you cannot download it in the field. Frame the area you're going to " +
                        "and the app saves it to this phone.",
                )
                val have = packs.orEmpty()
                if (have.isNotEmpty()) {
                    Text("Downloaded: " + have.joinToString { it.name }, fontWeight = FontWeight.Bold)
                    BigButton("Continue") { next() }
                    TextButton(onClick = onDownloadMap, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download another area") }
                } else {
                    BigButton("Choose area to download", onClick = onDownloadMap)
                    if (noMapAcknowledged) {
                        Text("You chose to continue without an offline map.")
                        TextButton(onClick = { next() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Continue without a map") }
                    } else {
                        TextButton(onClick = { confirmNoMap = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip") }
                    }
                }
            }

            Step.CanCant -> {
                // The range-honesty defense (§7.1). Do not soften this copy into marketing.
                Heading("What this mesh can and can't do")
                Text("Can", style = MaterialTheme.typography.titleMedium)
                Bullet("Works in airplane mode, as long as Bluetooth and Wi-Fi are switched on.")
                Bullet("Relays messages through teammates' phones to reach people further away.")
                Bullet("Keeps working with no cell towers, no Wi-Fi router, and no internet.")
                Text("Can't", style = MaterialTheme.typography.titleMedium)
                Bullet("Reach far in one hop: range is tens of meters per phone (${MeshConfig.PLANNING_RANGE_TEXT}). Distance comes from spacing people out as relays.")
                Bullet("Work unless phones have the app with Mesh active. If the \"Mesh active\" notification is gone, the mesh is off.")
                Bullet("Reach the internet or phone-number contacts.")
                Text("Mesh transport: Google Nearby Connections — requires Google Play Services on every phone.")
                Spacer(Modifier.padding(4.dp))
                BigButton("Finish setup") {
                    scope.launch {
                        app.identity.setOnboarded(true)
                        onDone()
                    }
                }
            }
        }
        if (index > 0) {
            TextButton(onClick = { index-- }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }
    }

    if (confirmNoMap) {
        AlertDialog(
            onDismissRequest = { confirmNoMap = false },
            title = { Text("No offline map?") },
            text = {
                Text(
                    "Without a downloaded map you'll see pins and teammates on a plain grid only, and you can't " +
                        "download a map once you're out of signal.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmNoMap = false
                        scope.launch { app.settings.setNoMapAcknowledged(true) }
                        next()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("I understand I will have no map offline") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNoMap = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download a map") }
            },
        )
    }
}

private fun rationale(group: PermissionGroup): Pair<String, String> = when (group) {
    PermissionGroup.NEARBY -> "Find teammates nearby" to
        "The mesh finds and connects to teammates' phones over Bluetooth and Wi-Fi. Without the Nearby devices " +
        "permission it can't discover anyone."
    PermissionGroup.LOCATION -> "Location" to
        "Android requires the location permission for Bluetooth and Wi-Fi discovery — the app has no server to " +
        "send your location to. It is also how your position shows on the team map and how GPS pins are dropped. " +
        "You can still drop pins by hand without it."
    PermissionGroup.NOTIFICATIONS -> "Notifications" to
        "While the mesh runs, a \"Mesh active\" notification shows how many teammates are nearby. If it's gone, the " +
        "mesh is off. Notifications also alert you when a teammate needs help."
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text")
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text)
    }
}
