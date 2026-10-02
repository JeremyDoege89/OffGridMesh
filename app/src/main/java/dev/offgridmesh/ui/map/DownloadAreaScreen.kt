package dev.offgridmesh.ui.map

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.offgridmesh.MeshApp
import dev.offgridmesh.maps.Bbox
import dev.offgridmesh.maps.MapPackManager
import dev.offgridmesh.maps.MapSource
import dev.offgridmesh.maps.MapStyle
import dev.offgridmesh.maps.PackJob
import dev.offgridmesh.maps.PmtilesExtractor
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
    else -> "%.0f KB".format(b / 1e3)
}

/**
 * Frame an area on a live (online) map and download it as an offline pack (§6). Needs internet; that is
 * the point of doing it before departure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadAreaScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext as MeshApp
    val job by app.mapPacks.job.collectAsStateWithLifecycle()
    var planetUrl by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var area by remember { mutableStateOf<Bbox?>(null) }
    var name by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        app.mapPacks.reset()
        try {
            planetUrl = MapSource.resolvePlanetUrl()
        } catch (_: Exception) {
            offline = true
        }
    }
    LaunchedEffect(map, planetUrl) {
        val m = map ?: return@LaunchedEffect
        val url = planetUrl ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromJson(MapStyle.build("pmtiles://$url")))
        val fix = lastKnown(context)
        m.moveCamera(
            if (fix != null) CameraUpdateFactory.newLatLngZoom(fix, 10.0)
            else CameraUpdateFactory.newLatLngZoom(LatLng(39.0, -96.0), 3.0),
        )
    }
    val busy = job is PackJob.Measuring || job is PackJob.Downloading
    // Keep the screen on while a download runs; it is app-scoped but the phone shouldn't sleep on it.
    DisposableEffect(busy) {
        val window = (context as? Activity)?.window
        if (busy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(job) {
        if (job is PackJob.Done) onBack()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text("Download a map area") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
        if (offline) {
            Text(
                "Downloading a map needs an internet connection. Connect to Wi-Fi and try again — you can't download " +
                    "maps in the field.",
                Modifier.padding(16.dp),
            )
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapLibreView(Modifier.fillMaxSize()) { m ->
                map = m
                m.addOnCameraIdleListener {
                    val b = m.projection.visibleRegion.latLngBounds
                    area = Bbox(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
                    if (job is PackJob.Ready || job is PackJob.Failed) app.mapPacks.reset()
                }
            }
            Box(Modifier.fillMaxSize().padding(2.dp).border(3.dp, Color(0xFFF2B33D)))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val b = area
            when (val j = job) {
                is PackJob.Measuring -> {
                    Text(j.text)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { app.mapPacks.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
                is PackJob.Ready -> {
                    Text("${j.name}: ${formatBytes(j.plan.downloadBytes)} · ${j.plan.tiles.size} tiles · zoom ${j.plan.minZoom}–${j.plan.maxZoom}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { app.mapPacks.download() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download") }
                        OutlinedButton(onClick = { app.mapPacks.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change area") }
                    }
                }
                is PackJob.Downloading -> {
                    Text("Downloading ${j.name}: ${formatBytes(j.doneBytes)} of ${formatBytes(j.totalBytes)}. Keep the app open.")
                    LinearProgressIndicator(
                        progress = { if (j.totalBytes > 0) j.doneBytes.toFloat() / j.totalBytes else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { app.mapPacks.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
                else -> {
                    if (j is PackJob.Failed) {
                        Card { Text(j.message, Modifier.padding(12.dp)) }
                    }
                    Text("Pan and zoom until the yellow frame covers your area. Everything on screen is downloaded.")
                    val tiles = b?.let { PmtilesExtractor.tileCount(it, MapPackManager.MIN_ZOOM, MapPackManager.MAX_ZOOM) }
                    if (tiles != null && tiles > PmtilesExtractor.MAX_TILES) {
                        Text("That's too large for one pack — zoom in.", color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Pack name") },
                        placeholder = { Text(b?.let { defaultName(it) } ?: "My area") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { b?.let { app.mapPacks.measure(it, name.ifBlank { defaultName(it) }) } },
                        enabled = b != null && planetUrl != null && (tiles ?: 0) <= PmtilesExtractor.MAX_TILES,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Check download size") }
                }
            }
        }
    }
}

private fun defaultName(b: Bbox) = "Area near %.2f, %.2f".format((b.south + b.north) / 2, (b.west + b.east) / 2)

@android.annotation.SuppressLint("MissingPermission")
private fun lastKnown(context: android.content.Context): LatLng? {
    if (!dev.offgridmesh.mesh.PermissionGroup.LOCATION.granted(context)) return null
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { LatLng(it.latitude, it.longitude) }
    } catch (_: SecurityException) {
        null
    }
}
