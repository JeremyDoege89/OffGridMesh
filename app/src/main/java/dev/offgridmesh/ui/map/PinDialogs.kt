package dev.offgridmesh.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.offgridmesh.data.PinRow
import dev.offgridmesh.sync.PinPayload
import dev.offgridmesh.sync.PinType
import dev.offgridmesh.ui.ago

fun PinType.label() = when (this) {
    PinType.HAZARD -> "Hazard"
    PinType.RALLY_POINT -> "Rally point"
    PinType.RESOURCE -> "Resource"
    PinType.OTHER -> "Other"
}

fun PinType.color() = when (this) {
    PinType.HAZARD -> Color(0xFFD32F2F)
    PinType.RALLY_POINT -> Color(0xFF2E7D32)
    PinType.RESOURCE -> Color(0xFF1565C0)
    PinType.OTHER -> Color(0xFF6A1B9A)
}

@Composable
fun TypeDot(type: PinType) {
    Box(Modifier.size(14.dp).clip(CircleShape).background(type.color()))
}

/** Pick type, then label (§7.4). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinEditor(
    title: String,
    initialLabel: String = "",
    initialType: PinType? = null,
    onSave: (label: String, type: PinType) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(initialType) }
    var label by rememberSaveable { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.label()) },
                            leadingIcon = { TypeDot(t) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(PinPayload.MAX_LABEL) },
                    label = { Text("Label") },
                    singleLine = true,
                    supportingText = { Text("${label.length}/${PinPayload.MAX_LABEL}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { type?.let { onSave(label, it) } },
                enabled = type != null && label.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save pin") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinDetailSheet(pin: PinRow, now: Long, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeDot(pin.type)
                Text(pin.type.label(), style = MaterialTheme.typography.labelLarge)
            }
            Text(pin.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Dropped by ${pin.creatorName.ifBlank { "Unnamed" }} · ${ago(pin.createdAtMs, now)}")
            if (pin.updatedAtMs != pin.createdAtMs) Text("Last changed ${ago(pin.updatedAtMs, now)}")
            Text("%.5f, %.5f".format(pin.lat, pin.lng), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this pin for everyone?") },
            text = { Text("The delete spreads to every teammate's phone as the mesh syncs.") },
            confirmButton = {
                Button(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") }
            },
        )
    }
}
