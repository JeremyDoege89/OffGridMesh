package dev.offgridmesh.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dev.offgridmesh.MeshApp
import dev.offgridmesh.billing.ProFeature
import dev.offgridmesh.billing.ProState
import dev.offgridmesh.crypto.TeamMode
import dev.offgridmesh.data.MemberStatusEntity
import dev.offgridmesh.data.Node
import dev.offgridmesh.sync.MemberState
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import dev.offgridmesh.mesh.MeshConfig
import dev.offgridmesh.mesh.MeshPermissions
import dev.offgridmesh.mesh.MeshService
import dev.offgridmesh.mesh.MeshState
import dev.offgridmesh.mesh.PermissionGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MeshUi(
    val mesh: MeshState = MeshState(),
    val selfId: String = "",
    val selfName: String = "",
    val nodes: List<Node> = emptyList(),
    val statuses: List<MemberStatusEntity> = emptyList(),
    val team: TeamMode? = null,
    val pro: ProState = ProState.FREE,
)

class MeshViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeshApp
    val ui = combine(
        combine(app.meshState, app.identity.identity, app.db.nodes().observeAll(), ::Triple),
        app.status.observeAll(),
        app.team.mode,
        app.pro.status,
    ) { (mesh, me, nodes), statuses, team, pro ->
        MeshUi(mesh, me.deviceId, me.displayName, nodes.filter { it.deviceId != me.deviceId }, statuses, team, pro.state)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeshUi())

    fun setStatus(state: MemberState, note: String?, lat: Double?, lng: Double?) =
        viewModelScope.launch { app.status.set(state, note, lat, lng) }
}

/** Mesh home (§7.2): the toggle, honest node accounting, and the node list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshScreen(onOpenSettings: () -> Unit, onChooseTeam: () -> Unit, vm: MeshViewModel = viewModel()) {
    val app = LocalContext.current.applicationContext as MeshApp
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }

    var canRun by remember { mutableStateOf(MeshPermissions.canRunMesh(context)) }
    var notificationsOn by remember { mutableStateOf(PermissionGroup.NOTIFICATIONS.granted(context)) }
    var askedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        canRun = MeshPermissions.canRunMesh(context)
        notificationsOn = PermissionGroup.NOTIFICATIONS.granted(context)
        onPauseOrDispose { }
    }
    val playServicesOk = remember {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        canRun = MeshPermissions.canRunMesh(context)
        notificationsOn = PermissionGroup.NOTIFICATIONS.granted(context)
    }

    val running = ui.mesh.running
    val teamChosen = ui.team != null && ui.team != TeamMode.Unset
    val roster = buildRoster(ui.selfId, ui.selfName, ui.nodes, ui.statuses, ui.mesh, now)
    val (visibleRoster, hiddenCount) = capRoster(roster, ui.pro)
    val visibleIds = visibleRoster.map { it.deviceId }.toSet()
    val direct = ui.mesh.connectedDeviceIds
    val acct = nodeAccounting(ui.mesh, ui.nodes, now)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Mesh") },
            actions = {
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (running) "Mesh active" else "Mesh off",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (running) "${acct.total} nodes — ${acct.direct} direct, ${acct.viaRelay} via relay"
                                else "Turn on to find and relay for your team.",
                            )
                        }
                        Switch(
                            checked = running,
                            enabled = canRun && playServicesOk && teamChosen,
                            onCheckedChange = { on -> if (on) MeshService.start(context) else MeshService.stop(context) },
                        )
                    }
                    // Padlock state is always visible (§10): open mesh must never look like the secure one.
                    when (ui.team) {
                        is TeamMode.Passphrase -> Text(
                            "🔒 Team passphrase encryption",
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                        TeamMode.Open -> Text(
                            "🔓 Open mesh — anyone nearby with the app can read everything",
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        else -> Unit
                    }
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        OwnStatusControls(ui.statuses.firstOrNull { it.deviceId == ui.selfId }, running) { st, note, lat, lng ->
                            vm.setStatus(st, note, lat, lng)
                        }
                    }
                }
            }

            if (ui.team == TeamMode.Unset) {
                item {
                    Notice("Choose your team's security before turning the mesh on: a shared team passphrase, or an open mesh.") {
                        Button(onClick = onChooseTeam, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set up team") }
                    }
                }
            }

            if (ui.mesh.foreignNearby > 0) {
                item {
                    Notice(
                        "${ui.mesh.foreignNearby} phone${if (ui.mesh.foreignNearby == 1) "" else "s"} nearby with the app " +
                            "use a different team passphrase (or an older version), so they can't join this mesh. " +
                            "Check everyone typed the same passphrase.",
                    )
                }
            }

            if (!playServicesOk) {
                item {
                    Notice(
                        "Google Play Services is missing or out of date on this phone. The mesh transport (Google " +
                            "Nearby Connections) needs it, so this phone can't join the mesh.",
                    )
                }
            } else if (!canRun) {
                item {
                    Notice(
                        "The mesh needs the Nearby devices permission" +
                            (if (android.os.Build.VERSION.SDK_INT <= 32) " and location" else "") +
                            " to discover teammates. Until it's allowed, no one can be found.",
                    ) {
                        Button(
                            onClick = {
                                launcher.launch(
                                    MeshPermissions.meshGroups().flatMap { it.permissions }.toTypedArray(),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Allow") }
                        if (askedOnce) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData(Uri.fromParts("package", context.packageName, null)),
                                    )
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Open app settings") }
                        }
                    }
                }
            }

            if (running && !notificationsOn) {
                item {
                    Notice("Notifications are off, so the \"Mesh active\" notification can't show. The mesh still runs.")
                }
            }

            if (running && direct.isEmpty()) {
                item {
                    Notice(
                        "Mesh active — no one in range yet. The mesh forms automatically when another member with " +
                            "the app comes within ${MeshConfig.PLANNING_RANGE_TEXT}. Keep Mesh active.",
                    )
                }
            }

            if (ui.nodes.isEmpty() && !running) {
                item {
                    Text(
                        "No teammates seen yet. Everyone turns Mesh on; phones within ${MeshConfig.PLANNING_RANGE_TEXT} " +
                            "find each other automatically.",
                        Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            items(ui.nodes.filter { it.deviceId in visibleIds }, key = { it.deviceId }) { node ->
                NodeRow(node, connected = node.deviceId in direct, now = now)
            }
            if (hiddenCount > 0) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { app.requestPaywall(ProFeature.NODES) },
                        headlineContent = { Text("+$hiddenCount more members") },
                        supportingContent = { Text("Free shows 8 members. Their messages, pins and help calls still arrive. Tap for Pro.") },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(node: Node, connected: Boolean, now: Long) {
    val stale = !connected && now - node.lastSeenMs >= MeshConfig.OFFLINE_AFTER_MS
    val where = when {
        connected -> "Direct · connected"
        node.hopCount > 0 && !stale -> "${hopLabel(node.hopCount)} · ${ago(node.lastSeenMs, now)}"
        else -> "Not connected · last seen ${ago(node.lastSeenMs, now)}"
    }
    val skew = node.clockSkewMs?.takeIf { kotlin.math.abs(it) > MeshConfig.CLOCK_WARN_MS }
    ListItem(
        modifier = Modifier.alpha(if (stale) 0.55f else 1f), // offline members are greyed, never removed (§9)
        headlineContent = { Text(node.displayName.ifBlank { "Unnamed" }, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                Text(where)
                if (skew != null) {
                    Text(
                        "Clock is ${skewText(skew)} — ask them to fix the time, or their edits may win conflicts.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = { node.batteryPct?.let { Text("$it%") } },
    )
}

private fun skewText(ms: Long): String {
    val m = kotlin.math.abs(ms) / 60_000
    val amount = when {
        m >= 2 * 1440 -> "${m / 1440} days"
        m >= 120 -> "${m / 60} hours"
        else -> "$m minutes"
    }
    return amount + if (ms > 0) " ahead" else " behind"
}

@Composable
private fun Notice(text: String, actions: @Composable () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}
