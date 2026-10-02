package dev.offgridmesh.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.offgridmesh.MeshApp
import dev.offgridmesh.data.ChecklistItemRow
import dev.offgridmesh.sync.ChecklistItemPayload
import dev.offgridmesh.sync.ChecklistPayload
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class TasksViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as MeshApp).checklists
    val lists = repo.observeLists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun list(id: String) = repo.observeList(id)
    fun items(id: String) = repo.observeItems(id)
    fun create(title: String, then: (String) -> Unit) = viewModelScope.launch { then(repo.createList(title)) }
    fun rename(id: String, title: String) = viewModelScope.launch { repo.renameList(id, title) }
    fun delete(id: String) = viewModelScope.launch { repo.deleteList(id) }
    fun add(listId: String, text: String) = viewModelScope.launch { repo.addItem(listId, text) }
    fun setDone(id: String, done: Boolean) = viewModelScope.launch { repo.setDone(id, done) }
    fun editItem(id: String, text: String) = viewModelScope.launch { repo.editItemText(id, text) }
    fun deleteItem(id: String) = viewModelScope.launch { repo.deleteItem(id) }
}

/** Shared checklists (§7.5). Writes are local-first and sync later — never blocked on a peer. */
@Composable
fun TasksScreen(vm: TasksViewModel = viewModel()) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val current = open
    if (current != null) ChecklistDetail(current, vm, onBack = { open = null }) else ChecklistList(vm, onOpen = { open = it })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistList(vm: TasksViewModel, onOpen: (String) -> Unit) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Checklists") })
            if (lists.isEmpty()) {
                ComingSoon(
                    "No checklists yet",
                    "Make one for the crew — \"Generator fueled?\", \"Radios charged?\". Everyone on the mesh sees the " +
                        "same list and who ticked what.",
                )
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(lists, key = { it.id }) { c ->
                    ListItem(
                        modifier = Modifier.combinedClickable(onClick = { onOpen(c.id) }),
                        headlineContent = { Text(c.title, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Column {
                                Text("${c.done}/${c.total} done")
                                if (c.total > 0) {
                                    LinearProgressIndicator(
                                        progress = { c.done.toFloat() / c.total },
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New checklist") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (creating) {
        TextPrompt("New checklist", "Title", "", ChecklistPayload.MAX_TITLE, "Create", onDismiss = { creating = false }) { t ->
            creating = false
            vm.create(t, onOpen)
        }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChecklistDetail(id: String, vm: TasksViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val list by remember(id) { vm.list(id) }.collectAsStateWithLifecycle(initialValue = null)
    val items by remember(id) { vm.items(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChecklistItemRow?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val l = list
    if (l != null && l.deleted) {
        onBack()
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(l?.title ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Delete checklist") }, onClick = { menu = false; deleting = true })
                }
            },
        )
        Text(
            "${items.count { it.done }}/${items.size} done · if two people change the same item while out of range, " +
                "the later change wins.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .combinedClickable(onClick = { vm.setDone(item.id, !item.done) }, onLongClick = { editing = item })
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = item.done, onCheckedChange = { vm.setDone(item.id, it) })
                    Column(Modifier.weight(1f)) {
                        Text(item.text, textDecoration = if (item.done) TextDecoration.LineThrough else null)
                        if (item.done) {
                            val at = item.doneAtMs?.let { timeFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                            Text(
                                "Done by ${item.doneByName ?: "someone"}" + (at?.let { ", $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(ChecklistItemPayload.MAX_TEXT) },
                placeholder = { Text("Add an item") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = { vm.add(id, draft); draft = "" },
                enabled = draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp).heightIn(min = 56.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "Add item") }
        }
    }

    if (renaming && l != null) {
        TextPrompt("Rename checklist", "Title", l.title, ChecklistPayload.MAX_TITLE, "Save", onDismiss = { renaming = false }) {
            renaming = false
            vm.rename(id, it)
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this checklist for everyone?") },
            text = { Text("The delete spreads to every teammate's phone as the mesh syncs.") },
            confirmButton = { Button(onClick = { deleting = false; vm.delete(id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } },
        )
    }
    editing?.let { item ->
        var text by rememberSaveable(item.id) { mutableStateOf(item.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit item") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ChecklistItemPayload.MAX_TEXT) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = { vm.editItem(item.id, text); editing = null }, enabled = text.isNotBlank()) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.deleteItem(item.id); editing = null }) { Text("Delete item") }
            },
        )
    }
}

@Composable
fun TextPrompt(
    title: String,
    label: String,
    initial: String,
    max: Int,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(max) },
                label = { Text(label) },
                singleLine = true,
                supportingText = { Text("${text.length}/$max") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}
