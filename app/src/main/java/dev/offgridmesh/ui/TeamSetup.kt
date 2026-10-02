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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.offgridmesh.MeshApp
import dev.offgridmesh.crypto.TeamCrypto
import kotlinx.coroutines.launch

/**
 * Team security (§10, decision D4): team passphrase (default) or an explicitly chosen open mesh.
 * The label is "Team passphrase encryption" — never "end-to-end" or "military-grade".
 */
@Composable
fun TeamSetupContent(onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshApp
    val scope = rememberCoroutineScope()
    var pass by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    val ok = TeamCrypto.normalize(pass).length >= TeamCrypto.MIN_PASSPHRASE

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Team passphrase", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Everyone on your team types the same passphrase. Phones with a different one can't join or read " +
                "anything. Use four or more random words — the passphrase is the whole protection.",
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Team passphrase") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (show) "Hide" else "Show")
                }
            },
            supportingText = { if (!ok) Text("At least ${TeamCrypto.MIN_PASSPHRASE} characters") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            onClick = {
                busy = true
                scope.launch {
                    app.team.setPassphrase(pass)
                    busy = false
                    pass = ""
                    onDone()
                }
            },
            enabled = ok && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Use this passphrase") }
        Text(
            "What it protects: people without the passphrase can't read or fake your team's traffic. What it doesn't: " +
                "anyone who learns the passphrase can read everything, and there's no per-person identity.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { confirmOpen = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Use an open mesh instead (public events)")
        }
    }
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Open mesh?") },
            text = {
                Text(
                    "Without a passphrase, anyone nearby running the app can read your messages, pins, checklists and " +
                        "help calls. Only use this for public events. The Mesh screen will show an open padlock.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmOpen = false
                    scope.launch { app.team.setOpen(); onDone() }
                }) { Text("Use open mesh") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmOpen = false }) { Text("Set a passphrase") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamSetupScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text("Team security") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Changing this turns the mesh off; turn it back on afterwards. Your messages, pins and checklists stay.",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            TeamSetupContent(onDone = onBack)
        }
    }
}
