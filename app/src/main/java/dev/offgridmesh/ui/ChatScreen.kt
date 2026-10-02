package dev.offgridmesh.ui

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.offgridmesh.MeshApp
import dev.offgridmesh.data.ChatRow
import dev.offgridmesh.data.MessageState
import dev.offgridmesh.data.Node
import dev.offgridmesh.mesh.MeshState
import dev.offgridmesh.sync.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class ChatUi(
    val rows: List<ChatRow> = emptyList(),
    val selfId: String = "",
    val mesh: MeshState = MeshState(),
    val nodes: List<Node> = emptyList(),
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeshApp
    val ui = combine(app.chat.observe(), app.identity.identity, app.meshState, app.db.nodes().observeAll()) {
            rows, me, mesh, nodes ->
        ChatUi(rows, me.deviceId, mesh, nodes.filter { it.deviceId != me.deviceId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUi())

    fun send(text: String) {
        viewModelScope.launch { app.chat.send(text) }
    }

    fun setVisible(visible: Boolean) {
        app.chatVisible = visible
    }
}

/** Single group thread (§7.3). Delay is the state, not failure: nothing here ever says "failed". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<ChatRow?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.rows.size) {
        if (ui.rows.isNotEmpty()) listState.animateScrollToItem(ui.rows.lastIndex)
    }

    val acct = nodeAccounting(ui.mesh, ui.nodes, now)
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text("Team chat")
                    Text(
                        if (ui.mesh.running) "${acct.total} nodes in range, ${acct.viaRelay} via relay"
                        else "Mesh off — new messages wait on this phone",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
        Box(Modifier.weight(1f)) {
            if (ui.rows.isEmpty()) {
                ComingSoon(
                    "No messages yet",
                    "Messages hop phone to phone through teammates. Write one now — if nobody is in range it waits " +
                        "on this phone and sends itself when someone is.",
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.rows, key = { it.id }) { row ->
                    Bubble(row, own = row.senderId == ui.selfId, onLongPress = { detail = row })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(ChatMessage.MAX_CHARS) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message the team") },
                supportingText = { Text("${draft.length}/${ChatMessage.MAX_CHARS}") },
                maxLines = 5,
            )
            FilledIconButton(
                onClick = {
                    vm.send(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp, bottom = 20.dp).size(56.dp),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }

    detail?.let { row ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("OK") } },
            title = { Text(if (row.senderId == ui.selfId) "Your message" else row.senderName.ifBlank { "Unnamed" }) },
            text = { Text(detailText(row, own = row.senderId == ui.selfId, now = now)) },
        )
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun clock(ms: Long) = timeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun stateLabel(state: MessageState) = when (state) {
    MessageState.LOCAL_ONLY -> "Queued"
    MessageState.RELAYED -> "Relayed"
    MessageState.DELIVERED -> "Delivered"
    MessageState.RECEIVED -> ""
}

private fun detailText(row: ChatRow, own: Boolean, now: Long): String = if (own) {
    when (row.state) {
        MessageState.LOCAL_ONLY ->
            "Queued on this phone. Nobody has it yet; it sends itself when a teammate comes in range."
        MessageState.RELAYED ->
            "Relayed: handed to at least one teammate and may still be traveling. " +
                "Acknowledged by ${row.ackedCount} of ${row.expectedCount} members known when it left."
        MessageState.DELIVERED -> "Delivered to all current members (${row.expectedCount})."
        MessageState.RECEIVED -> ""
    } + "\nWritten ${clock(row.timestampMs)}."
} else {
    val hops = row.relays + 1
    val path = if (row.relays == 0) "Direct from ${row.senderName.ifBlank { "Unnamed" }}, 1 hop"
    else "Relayed via ${row.viaName ?: "a teammate"}, $hops hops"
    "$path, received ${ago(row.receivedAtMs, now)}.\nWritten ${clock(row.timestampMs)}."
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(row: ChatRow, own: Boolean, onLongPress: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "Message details"),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!own) Text(row.senderName.ifBlank { "Unnamed" }, fontWeight = FontWeight.Bold)
                Text(row.text)
                val meta = buildList {
                    add(clock(row.timestampMs))
                    if (own) add(stateLabel(row.state))
                    else if (row.relays > 0) add("via ${row.viaName ?: "relay"}")
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}
