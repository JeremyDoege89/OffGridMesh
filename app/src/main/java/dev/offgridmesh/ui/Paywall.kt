package dev.offgridmesh.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offgridmesh.MeshApp
import dev.offgridmesh.billing.Entitlements
import dev.offgridmesh.billing.ProEvent
import dev.offgridmesh.billing.ProFeature
import dev.offgridmesh.billing.ProState
import kotlinx.coroutines.launch

data class PaywallRequest(val feature: ProFeature?)

/** List price until Play supplies the localised one (open decision D3). */
const val PRO_LIST_PRICE = "$9.99"

private fun headline(f: ProFeature?) = when (f) {
    ProFeature.NODES -> "Your mesh has more than ${Entitlements.FREE_NODES} members"
    ProFeature.MAP_PACKS -> "Keep more than one map area"
    ProFeature.TTL -> "Relay further"
    ProFeature.EXPORT -> "Export pins and checklists"
    null -> "Off-Grid Mesh Pro"
}

private fun pitch(f: ProFeature?) = when (f) {
    ProFeature.NODES ->
        "Free shows up to ${Entitlements.FREE_NODES} members. Pro shows everyone on the mesh — for event teams and crews " +
            "of any size. Messages, pins and help calls from every member keep arriving either way."
    ProFeature.MAP_PACKS -> "Free keeps one downloaded map area. Pro keeps as many as you need for the trip."
    ProFeature.TTL -> "Pro lets your messages travel 4 or 5 phone-to-phone hops instead of up to 3."
    ProFeature.EXPORT -> "Pro saves your pins (GeoJSON) and checklists (JSON) to a file."
    null -> "One payment unlocks unlimited members, more map areas, longer relay and export. No subscription."
}

/**
 * Build Guide §7.8: Free vs Pro, one-time price, restore. Declining changes nothing about the mesh.
 * Automatic offers never appear during an active NEED_HELP (AppRoot defers them).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Paywall(request: PaywallRequest, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshApp
    val activity = LocalActivity.current
    val status by app.pro.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val price = status.price ?: PRO_LIST_PRICE
    LaunchedEffect(Unit) {
        app.pro.events.collect { e ->
            message = when (e) {
                ProEvent.Unlocked -> "Pro unlocked. Thank you!"
                ProEvent.Pending -> null
                is ProEvent.Failed -> e.message
                is ProEvent.Restored -> if (e.state == ProState.PRO) "Pro restored." else "No Pro purchase found for this Google account."
                ProEvent.RestoreOffline -> "Can't reach Google Play right now. Try Restore when you're online."
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(headline(request.feature), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(pitch(request.feature), style = MaterialTheme.typography.bodyLarge)
            Comparison(price)
            when (status.state) {
                ProState.PENDING -> Notice("Payment pending. Pro unlocks as soon as Google Play confirms it; you don't need to buy again.")
                ProState.PRO -> Notice("Pro is unlocked on this phone.")
                ProState.FREE -> Unit
            }
            message?.let { Notice(it) }
            if (status.state == ProState.FREE) {
                Button(
                    onClick = {
                        if (activity != null && !busy) {
                            busy = true
                            scope.launch { try { app.pro.buy(activity) } finally { busy = false } }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("Get Pro · $price once") }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (status.state == ProState.FREE) "Not now" else "Close")
            }
            TextButton(
                onClick = { scope.launch { busy = true; try { app.pro.restore() } finally { busy = false } } },
                enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
            ) { Text("Restore purchases") }
            Text(
                "The mesh, chat, pins, checklists and help calls work fully on Free. Nothing stops if you say no.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp))
    }
}

private enum class Cell { YES, NO }

private class Feature(val label: String, val free: Any, val pro: Any)

private val FEATURES = listOf(
    Feature("Mesh chat, pins, checklists, status, encryption", Cell.YES, Cell.YES),
    Feature("Members shown", "${Entitlements.FREE_NODES}", "Unlimited"),
    Feature("Map areas", "${Entitlements.FREE_MAP_PACKS}", "Unlimited"),
    Feature("Relay hops", "1–${Entitlements.FREE_MAX_TTL}", "1–5"),
    Feature("Export pins & checklists", Cell.NO, Cell.YES),
)

@Composable
private fun Comparison(price: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1.6f))
            Header("Free", "$0")
            Header("Pro", "$price once", highlight = true)
        }
        FEATURES.forEach { f ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.6f).padding(vertical = 8.dp))
                Value(f.free, "Free", f.label)
                Value(f.pro, "Pro", f.label)
            }
        }
    }
}

@Composable
private fun RowScope.Header(name: String, sub: String, highlight: Boolean = false) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            name, style = MaterialTheme.typography.titleSmall, fontWeight = if (highlight) FontWeight.Bold else null,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(sub, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RowScope.Value(v: Any, tier: String, feature: String) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        when (v) {
            Cell.YES -> Icon(Icons.Filled.Check, contentDescription = "$tier includes $feature", tint = MaterialTheme.colorScheme.primary)
            Cell.NO -> Text("—", modifier = Modifier.clearAndSetSemantics { contentDescription = "$tier doesn't include $feature" })
            else -> Text(v.toString(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}
