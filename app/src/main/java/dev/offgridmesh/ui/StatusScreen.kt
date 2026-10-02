package dev.offgridmesh.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.offgridmesh.MeshApp
import dev.offgridmesh.billing.ProFeature
import dev.offgridmesh.billing.ProState
import dev.offgridmesh.data.Identity
import dev.offgridmesh.data.MemberStatusEntity
import dev.offgridmesh.data.Node
import dev.offgridmesh.mesh.MeshState
import dev.offgridmesh.mesh.PermissionGroup
import dev.offgridmesh.sync.MemberState
import dev.offgridmesh.sync.StatusPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val HelpRed = Color(0xFFD32F2F)

data class StatusUi(
    val me: Identity? = null,
    val nodes: List<Node> = emptyList(),
    val statuses: List<MemberStatusEntity> = emptyList(),
    val mesh: MeshState = MeshState(),
    val pro: ProState = ProState.FREE,
)

class StatusViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeshApp
    val ui = combine(app.identity.identity, app.db.nodes().observeAll(), app.status.observeAll(), app.meshState, app.pro.status) {
            me, nodes, statuses, mesh, pro ->
        StatusUi(me, nodes, statuses, mesh, pro.state)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUi())

    fun set(state: MemberState, note: String? = null, lat: Double? = null, lng: Double? = null) =
        viewModelScope.launch { app.status.set(state, note, lat, lng) }
}

@SuppressLint("MissingPermission")
fun lastFix(context: Context): android.location.Location? {
    if (!PermissionGroup.LOCATION.granted(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }
}

/** I'm OK / I need help (§7.6): on the status board and in the Mesh header. */
@Composable
fun OwnStatusControls(own: MemberStatusEntity?, meshRunning: Boolean, onSet: (MemberState, String?, Double?, Double?) -> Unit) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val helping = own?.state == MemberState.NEED_HELP
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (helping) {
            Text("You asked for help" + (own?.note?.let { ": \"$it\"" } ?: "") + ".", color = HelpRed, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { if (helping) clearing = true else onSet(MemberState.OK, null, null, null) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text("I'm OK") }
            Button(
                onClick = { asking = true },
                colors = ButtonDefaults.buttonColors(containerColor = HelpRed, contentColor = Color.White),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(if (helping) "Update help call" else "I need help") }
        }
    }
    if (asking) {
        var note by rememberSaveable { mutableStateOf(own?.note.orEmpty()) }
        val canLocate = PermissionGroup.LOCATION.granted(context)
        var withPosition by rememberSaveable { mutableStateOf(canLocate) }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Ask the team for help?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everyone on the mesh gets a loud alert and you go to the top of their status board until you tap I'm OK.")
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(StatusPayload.MAX_NOTE) },
                        label = { Text("What's wrong? (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = canLocate) { withPosition = !withPosition }) {
                        Checkbox(checked = withPosition, onCheckedChange = { withPosition = it }, enabled = canLocate)
                        Text(if (canLocate) "Include my position" else "Position unavailable (location is off for this app)")
                    }
                    if (!meshRunning) Text("Mesh is off — the call waits on this phone until you turn Mesh on.", color = HelpRed)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fix = if (withPosition) lastFix(context) else null
                        onSet(MemberState.NEED_HELP, note, fix?.latitude, fix?.longitude)
                        asking = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HelpRed, contentColor = Color.White),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Send help call") }
            },
            dismissButton = { TextButton(onClick = { asking = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear your help call?") },
            text = { Text("Your teammates will see you're OK.") },
            confirmButton = {
                Button(onClick = { onSet(MemberState.OK, null, null, null); clearing = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("I'm OK now")
                }
            },
            dismissButton = { TextButton(onClick = { clearing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep it") } },
        )
    }
}

/** Roll-call (§7.6): NEED_HELP first in red on every phone, offline greyed, never removed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(vm: StatusViewModel = viewModel()) {
    val app = LocalContext.current.applicationContext as MeshApp
    val ui by vm.ui.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val me = ui.me ?: return
    val roster = buildRoster(me.deviceId, me.displayName, ui.nodes, ui.statuses, ui.mesh, now)
    val (visible, hidden) = capRoster(roster, ui.pro)
    val own = ui.statuses.firstOrNull { it.deviceId == me.deviceId }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Status") })
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        OwnStatusControls(own, ui.mesh.running) { s, note, lat, lng -> vm.set(s, note, lat, lng) }
                    }
                }
            }
            items(visible, key = { it.deviceId }) { m -> MemberRow(m, now) }
            if (hidden > 0) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { app.requestPaywall(ProFeature.NODES) },
                        headlineContent = { Text("+$hidden more members") },
                        supportingContent = { Text("Free shows 8 members. Anyone who needs help always shows. Tap for Pro.") },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(m: Member, now: Long) {
    val s = m.status
    val label = when {
        m.needsHelp -> "NEEDS HELP"
        m.offline -> "Offline"
        s?.state == MemberState.OK -> "OK"
        else -> "No status yet"
    }
    val seen = when {
        m.isSelf -> "This phone"
        m.connected -> "Direct · in range now"
        m.node != null -> "Last seen ${ago(m.node.lastSeenMs, now)}"
        else -> ""
    }
    val detail = buildList {
        add(seen)
        s?.let { add("status set ${ago(it.updatedAtMs, now)}") }
        if (m.needsHelp && s?.lat != null) add("position %.5f, %.5f".format(s.lat, s.lng))
    }.filter { it.isNotEmpty() }.joinToString(" · ")
    ListItem(
        modifier = Modifier.alpha(if (m.offline && !m.needsHelp) 0.55f else 1f),
        colors = if (m.needsHelp) ListItemDefaults.colors(containerColor = HelpRed.copy(alpha = 0.15f)) else ListItemDefaults.colors(),
        headlineContent = {
            Text(m.name + if (m.isSelf) " (you)" else "", fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Column {
                if (m.needsHelp && s?.note != null) Text("\"${s.note}\"", color = HelpRed, fontWeight = FontWeight.Bold)
                Text(detail)
            }
        },
        trailingContent = {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                color = when {
                    m.needsHelp -> HelpRed
                    s?.state == MemberState.OK && !m.offline -> Color(0xFF2E7D32)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    )
}
