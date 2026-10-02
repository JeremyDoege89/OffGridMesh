package dev.offgridmesh.ui.map

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.offgridmesh.MeshApp
import dev.offgridmesh.data.MapPack
import dev.offgridmesh.data.Node
import dev.offgridmesh.data.PinRow
import dev.offgridmesh.maps.Bbox
import dev.offgridmesh.maps.MapGeo
import dev.offgridmesh.maps.MapPackManager
import dev.offgridmesh.maps.MapStyle
import dev.offgridmesh.mesh.MeshConfig
import dev.offgridmesh.mesh.PermissionGroup
import dev.offgridmesh.sync.PinType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

data class MapUi(
    val packs: List<MapPack> = emptyList(),
    val pins: List<PinRow> = emptyList(),
    val nodes: List<Node> = emptyList(),
    val shareLocation: Boolean = false,
    val loaded: Boolean = false,
)

class MapViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeshApp
    val ui = combine(
        app.mapPacks.packs, app.pins.observe(), app.db.nodes().observeAll(), app.identity.identity, app.settings.shareLocation,
    ) { packs, pins, nodes, me, share ->
        MapUi(packs, pins, nodes.filter { it.deviceId != me.deviceId }, share, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUi())

    fun packFile(p: MapPack) = app.mapPacks.file(p)
    fun create(lat: Double, lng: Double, label: String, type: PinType) = viewModelScope.launch { app.pins.create(lat, lng, label, type) }
    fun edit(id: String, label: String, type: PinType) = viewModelScope.launch { app.pins.edit(id, label, type) }
    fun delete(id: String) = viewModelScope.launch { app.pins.delete(id) }
    fun setShareLocation(on: Boolean) = viewModelScope.launch { app.settings.setShareLocation(on) }
}

private fun noFixHint(permission: Boolean) =
    if (permission) "No GPS fix yet. Long-press the map to drop a pin by hand."
    else "Location is off for this app. Long-press the map to drop a pin by hand."

private fun covering(packs: List<MapPack>, lat: Double, lng: Double) =
    packs.filter { it.bbox.contains(lat, lng) }.minByOrNull { (it.east - it.west) * (it.north - it.south) }

@SuppressLint("MissingPermission")
private fun lastFix(context: Context): Location? {
    if (!PermissionGroup.LOCATION.granted(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }
}

/** Map (§7.4): offline packs, pins by type, teammates' shared positions, own GPS, grid fallback (§6). */
@Composable
fun MapScreen(onOpenPacks: () -> Unit, vm: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val ui by vm.ui.collectAsStateWithLifecycle()

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var activePack by remember { mutableStateOf<MapPack?>(null) }
    var styledFor by remember { mutableStateOf<String?>("unset") }
    var visible by remember { mutableStateOf<Bbox?>(null) }
    var scale by remember { mutableStateOf("" to 0f) }
    var cameraPlaced by remember { mutableStateOf(false) }
    var hasLocationPermission by remember { mutableStateOf(PermissionGroup.LOCATION.granted(context)) }
    var dropAt by remember { mutableStateOf<LatLng?>(null) }
    var selectedPin by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PinRow?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(5_000)
            hint = null
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    LifecycleResumeEffect(Unit) {
        hasLocationPermission = PermissionGroup.LOCATION.granted(context)
        onPauseOrDispose { }
    }

    fun updateScale(m: MapLibreMap) {
        val lat = m.cameraPosition.target?.latitude ?: return
        val mpp = m.projection.getMetersPerPixelAtLatitude(lat)
        val maxPx = with(density) { 96.dp.toPx() }
        val nice = MapGeo.niceDistance(mpp * maxPx)
        scale = MapGeo.formatDistance(nice) to with(density) { (nice / mpp).toFloat().toDp().value }
    }

    // Pick the pack under the camera; restyle only when that changes.
    val center = visible?.let { (it.south + it.north) / 2 to (it.west + it.east) / 2 }
    val packUnderCamera = center?.let { covering(ui.packs, it.first, it.second) }
    LaunchedEffect(packUnderCamera, ui.packs) {
        activePack = packUnderCamera ?: activePack?.takeIf { it in ui.packs } ?: ui.packs.firstOrNull()
    }
    LaunchedEffect(map, activePack, ui.loaded) {
        val m = map ?: return@LaunchedEffect
        if (!ui.loaded) return@LaunchedEffect
        val key = activePack?.id
        if (styledFor == key) return@LaunchedEffect
        styledFor = key
        val url = activePack?.let { "mbtiles://" + vm.packFile(it).absolutePath }
        m.setStyle(Style.Builder().fromJson(MapStyle.build(url))) { s -> style = s }
    }

    // First camera placement: own fix, else the first pack, else the world.
    LaunchedEffect(map, ui.loaded) {
        val m = map ?: return@LaunchedEffect
        if (cameraPlaced || !ui.loaded) return@LaunchedEffect
        cameraPlaced = true
        val fix = lastFix(context)
        val p = ui.packs.firstOrNull()
        when {
            fix != null -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 13.0))
            p != null -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng((p.south + p.north) / 2, (p.west + p.east) / 2), 11.0))
            else -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 1.0))
        }
        val b = m.projection.visibleRegion.latLngBounds
        visible = Bbox(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
        updateScale(m)
    }

    @SuppressLint("MissingPermission")
    fun enableLocation(m: MapLibreMap, s: Style) {
        if (!PermissionGroup.LOCATION.granted(context)) return
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, s).useDefaultLocationEngine(true).build(),
            )
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.NORMAL
    }
    LaunchedEffect(style, hasLocationPermission) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        enableLocation(m, s)
    }

    // Overlays.
    LaunchedEffect(style, ui.pins) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.PINS)?.setGeoJson(
            MapGeo.points(ui.pins.map { MapGeo.Point(it.lat, it.lng, mapOf("id" to it.id, "label" to it.label, "type" to it.type.name)) }),
        )
    }
    LaunchedEffect(style, ui.nodes, now) {
        style?.getSourceAs<GeoJsonSource>(MapStyle.NODES)?.setGeoJson(
            MapGeo.points(
                ui.nodes.filter { it.lat != null && it.lng != null }.map {
                    val stale = now - (it.locationAtMs ?: 0) > MeshConfig.OFFLINE_AFTER_MS
                    MapGeo.Point(it.lat!!, it.lng!!, mapOf("name" to it.displayName.ifBlank { "Unnamed" }, "stale" to stale))
                },
            ),
        )
    }
    val centerCovered = center != null && covering(ui.packs, center.first, center.second) != null
    LaunchedEffect(style, visible, centerCovered) {
        val b = visible
        style?.getSourceAs<GeoJsonSource>(MapStyle.GRID)?.setGeoJson(
            if (b != null && !centerCovered) MapGeo.grid(b) else MapGeo.points(emptyList()),
        )
    }

    val fix = if (hasLocationPermission) map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
        ?: lastFix(context) else null

    Box(Modifier.fillMaxSize()) {
        MapLibreView(Modifier.fillMaxSize()) { m ->
            map = m
            m.addOnCameraIdleListener {
                val b = m.projection.visibleRegion.latLngBounds
                visible = Bbox(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
                updateScale(m)
            }
            m.addOnCameraMoveListener { updateScale(m) }
            m.addOnMapClickListener { point ->
                val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(point), MapStyle.PIN_LAYER).firstOrNull()
                selectedPin = hit?.getStringProperty("id")
                hit != null
            }
            m.addOnMapLongClickListener { point ->
                dropAt = point
                true
            }
        }

        Column(
            // Leaves the top-right corner to the map-packs button.
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 72.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val banner = when {
                !ui.loaded -> null
                ui.packs.isEmpty() ->
                    "No offline map on this phone. Pins and teammates still show at their true positions on a grid. " +
                        "Download a map while you have internet."
                visible != null && !centerCovered -> "No downloaded map covers this area — showing a grid."
                fix != null && covering(ui.packs, fix.latitude, fix.longitude) == null ->
                    "Your position is outside your downloaded maps."
                else -> null
            }
            if (banner != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(banner)
                        if (ui.packs.isEmpty()) TextButton(onClick = onOpenPacks, modifier = Modifier.heightIn(min = 48.dp)) { Text("Map packs") }
                    }
                }
            }
            hint?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(it, Modifier.padding(12.dp))
                }
            }
            FilterChip(
                selected = ui.shareLocation,
                onClick = { vm.setShareLocation(!ui.shareLocation) },
                label = { Text(if (ui.shareLocation) "Sharing my location" else "Not sharing my location") },
                enabled = hasLocationPermission,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        FilledTonalIconButton(
            onClick = onOpenPacks,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) { Icon(Icons.Filled.Layers, contentDescription = "Map packs") }

        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            if (scale.second > 0) {
                Text(scale.first, style = MaterialTheme.typography.labelMedium, color = Color(0xFF333333))
                Box(Modifier.width(scale.second.dp).height(4.dp).background(Color(0xFF333333)))
            }
            Text(
                MapPackManager.ATTRIBUTION + " · MapLibre",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF333333),
                modifier = Modifier.padding(top = 4.dp).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp),
            )
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    if (fix == null) hint = noFixHint(hasLocationPermission)
                    else map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 15.0))
                },
            ) { Icon(Icons.Filled.MyLocation, contentDescription = "Center on my position") }
            SmallFloatingActionButton(
                onClick = {
                    if (fix == null) hint = noFixHint(hasLocationPermission)
                    else dropAt = LatLng(fix.latitude, fix.longitude)
                },
            ) { Icon(Icons.Filled.AddLocationAlt, contentDescription = "Drop pin at my position") }
        }
    }

    dropAt?.let { at ->
        PinEditor(
            title = "Drop pin",
            onSave = { label, type ->
                vm.create(at.latitude, at.longitude, label, type)
                dropAt = null
            },
            onDismiss = { dropAt = null },
        )
    }
    ui.pins.firstOrNull { it.id == selectedPin }?.let { pin ->
        PinDetailSheet(
            pin = pin,
            now = now,
            onEdit = { editing = pin; selectedPin = null },
            onDelete = { vm.delete(pin.id); selectedPin = null },
            onDismiss = { selectedPin = null },
        )
    }
    editing?.let { pin ->
        PinEditor(
            title = "Edit pin",
            initialLabel = pin.label,
            initialType = pin.type,
            onSave = { label, type ->
                vm.edit(pin.id, label, type)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}
