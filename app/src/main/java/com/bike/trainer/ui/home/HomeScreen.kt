package com.bike.trainer.ui.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.data.RiderSettings
import com.bike.trainer.data.Scenery
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.physics.VirtualGears
import com.bike.trainer.route.GpxImporter
import com.bike.trainer.route.Route
import com.bike.trainer.route.RouteGenerator
import com.bike.trainer.route.RouteLibrary
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.route.StreetViewManifest
import com.bike.trainer.route.StreetViewPrefetcher
import com.bike.trainer.route.TerrainTiles
import com.bike.trainer.session.RideEngine
import com.bike.trainer.session.Workout
import com.bike.trainer.session.WorkoutLibrary
import com.bike.trainer.ui.theme.workoutZoneColor
import com.bike.trainer.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onViewStats: () -> Unit,
    onStartRide: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trainer = ServiceLocator.trainerConnection
    val hrm = ServiceLocator.heartRateManager
    val settingsRepo = ServiceLocator.settingsRepository
    val profileRepo = ServiceLocator.profileRepository

    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = RiderSettings())
    val activeEntry by profileRepo.active.collectAsStateWithLifecycle(initialValue = null)
    val appConfig by ServiceLocator.appConfigRepository.config.collectAsStateWithLifecycle(initialValue = null)
    val scenery = appConfig?.scenery ?: Scenery.STREET_VIEW
    val stylized = scenery == Scenery.STYLIZED

    // Selected workout (null = Freeride). Declared before startRide so it captures it.
    var selectedWorkout by remember { mutableStateOf<Workout?>(null) }

    val startRide: (Route, Double, Boolean) -> Unit = { route, startMeters, ignoreHills ->
        ServiceLocator.scenery = scenery
        ServiceLocator.photoTexture = appConfig?.photoTexture ?: true
        ServiceLocator.activeRide = RideEngine(
            route = route,
            trainer = trainer,
            riderMassKg = activeEntry?.profile?.weightKg ?: 75.0,
            gears = VirtualGears(gearCount = settings.gearCount),
            heartRateManager = hrm,
            startDistanceMeters = startMeters,
            ignoreHills = ignoreHills,
            workout = selectedWorkout,
            ftp = activeEntry?.profile?.ftpWatts ?: 200,
        )
        onStartRide()
    }

    fun loadAndStart(name: String, id: String?, startMeters: Double, ignoreHills: Boolean, open: () -> java.io.InputStream?) {
        scope.launch {
            val route = withContext(Dispatchers.IO) {
                runCatching { open()?.use { GpxImporter.import(name, it, id) } }.getOrNull()
            }
            if (route != null) startRide(route, startMeters, ignoreHills)
            else Toast.makeText(context, "Couldn't read that GPX route", Toast.LENGTH_LONG).show()
        }
    }

    var gpxFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var selectedGpx by remember { mutableStateOf<java.io.File?>(null) }
    // "Random route" is the synthetic last dropdown entry (no GPX file).
    var randomSelected by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var prefetchFor by remember { mutableStateOf<java.io.File?>(null) }
    var stylizeFor by remember { mutableStateOf<Route?>(null) }
    // Selected GPX parsed, and how many of its terrain tiles are still missing.
    var selectedRoute by remember { mutableStateOf<Route?>(null) }
    var missingTiles by remember { mutableStateOf<Int?>(null) }
    // Prefetched Street View frames of the selected GPX (for photo-textured Stylized).
    var svFrames by remember { mutableStateOf<StreetViewManifest?>(null) }
    var startKm by remember { mutableStateOf("") }
    var ignoreHills by remember { mutableStateOf(false) }
    var workoutMenuExpanded by remember { mutableStateOf(false) }
    val ftp = activeEntry?.profile?.ftpWatts ?: 200
    // (km, total ascent m) of the selected GPX, shown on the Start button.
    var selectedStats by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    LaunchedEffect(selectedGpx, randomSelected) {
        selectedStats = null
        selectedRoute = null
        missingTiles = null
        svFrames = null
        val file = selectedGpx
        if (file != null && !randomSelected) {
            val route = withContext(Dispatchers.IO) {
                runCatching {
                    file.inputStream().use { GpxImporter.import(file.nameWithoutExtension, it, file.nameWithoutExtension) }
                }.getOrNull()
            }
            if (route != null && file == selectedGpx) {
                selectedStats = route.totalDistance / 1000.0 to route.totalAscent
                selectedRoute = route
                val frames = withContext(Dispatchers.IO) { StreetViewCache.loadFor(context, route) }
                svFrames = frames
                missingTiles = withContext(Dispatchers.IO) {
                    TerrainTiles.missing(context, route).let { it.first + it.second } +
                        (frames?.let { it.unlocatedCount + com.bike.trainer.ui.ride.PhotoFrames.unpainted(context, it).size } ?: 0)
                }
            }
        }
    }

    fun refreshGpx(select: java.io.File? = null) {
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                RouteLibrary.ensureSeeded(context)
                RouteLibrary.list(context)
            }
            gpxFiles = files
            selectedGpx = select
                ?: selectedGpx?.takeIf { sel -> files.any { it.path == sel.path } }
                ?: files.firstOrNull()
        }
    }

    LaunchedEffect(Unit) { refreshGpx() }

    // The picker now copies the chosen file into the routes folder so it persists
    // and shows up in the dropdown, instead of being a one-off import.
    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) {
                    RouteLibrary.importInto(context, uri, displayName(context, uri))
                }
                if (copied != null) refreshGpx(select = copied)
                else Toast.makeText(context, "Couldn't add that GPX file", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                Text("  Setup")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onViewStats) { Text("Stats") }
        }
        Text(
            "Ready, ${activeEntry?.profile?.name ?: "rider"} — pick a route",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // ---- Route ----
        SectionCard {
            Text("Route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            val selectionLabel = when {
                randomSelected -> "🎲 Random route"
                selectedGpx != null -> RouteLibrary.prettyName(selectedGpx!!)
                else -> "Select a route"
            }
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = !menuExpanded },
            ) {
                OutlinedTextField(
                    value = selectionLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Route") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    gpxFiles.forEach { file ->
                        DropdownMenuItem(
                            text = { Text(RouteLibrary.prettyName(file)) },
                            onClick = {
                                selectedGpx = file
                                randomSelected = false
                                menuExpanded = false
                            },
                        )
                    }
                    // Random generated corridor — always the last option.
                    DropdownMenuItem(
                        text = { Text("🎲 Random route") },
                        onClick = {
                            randomSelected = true
                            selectedGpx = null
                            menuExpanded = false
                        },
                    )
                }
            }

            // Start-at-km only applies to a real GPX route.
            if (selectedGpx != null && !randomSelected) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = startKm,
                    onValueChange = { s -> startKm = s.filter { it.isDigit() || it == '.' } },
                    label = { Text("Start at km (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { gpxPicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.UploadFile, contentDescription = null)
                Text("  Add GPX")
            }
            // Prefetch Google Street View frames along the selected route so the
            // ride plays them smoothly and offline (needs a Google Maps key).
            if (selectedGpx != null && !randomSelected && com.bike.trainer.BuildConfig.HAS_MAPS_KEY) {
                TextButton(onClick = { prefetchFor = selectedGpx }) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Text("  Prefetch Street View…")
                }
            }

            // ---- Scenery: Street View imagery, Google's 3D city models, or our own 3D world ----
            Spacer(Modifier.height(8.dp))
            Text("Scenery", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(
                    "Street View" to Scenery.STREET_VIEW,
                    "Google 3D" to Scenery.GOOGLE_3D,
                    "Stylized" to Scenery.STYLIZED,
                )
                options.forEachIndexed { i, (label, value) ->
                    SegmentedButton(
                        selected = scenery == value,
                        onClick = { scope.launch { ServiceLocator.appConfigRepository.setScenery(value) } },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            if (scenery == Scenery.GOOGLE_3D) {
                Text(
                    if (randomSelected) "Random routes aren't a real place — they'll use Stylized."
                    else "Google's real 3D models of buildings, trees and terrain, streamed live. " +
                        "Needs internet (Google doesn't allow saving it offline).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stylized) {
                val r = selectedRoute
                if (randomSelected) {
                    Text(
                        "Random routes get generated hills — nothing to download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (r != null) {
                    val missing = missingTiles
                    TextButton(onClick = { stylizeFor = r }) {
                        Icon(Icons.Filled.Terrain, contentDescription = null)
                        Text(if (missing == 0) "  Stylized ✓ (works offline)" else "  Stylize…")
                    }
                    val frames = svFrames
                    val photosOn = appConfig?.photoTexture ?: true
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = frames != null) {
                            scope.launch { ServiceLocator.appConfigRepository.setPhotoTexture(!photosOn) }
                        },
                    ) {
                        Checkbox(
                            checked = frames != null && photosOn,
                            enabled = frames != null,
                            onCheckedChange = { on -> scope.launch { ServiceLocator.appConfigRepository.setPhotoTexture(on) } },
                        )
                        Text(
                            if (frames != null) "Paint it from my Street View photos (${frames.imageCount})"
                            else "Paint from Street View photos — prefetch them first",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // ---- Workout ----
        SectionCard {
            Text("Workout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = workoutMenuExpanded,
                onExpandedChange = { workoutMenuExpanded = !workoutMenuExpanded },
            ) {
                OutlinedTextField(
                    value = workoutLabel(selectedWorkout),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Workout") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workoutMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = workoutMenuExpanded,
                    onDismissRequest = { workoutMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Freeride") },
                        onClick = { selectedWorkout = null; workoutMenuExpanded = false },
                    )
                    WorkoutLibrary.all.forEach { w ->
                        DropdownMenuItem(
                            text = { Text(workoutLabel(w)) },
                            onClick = { selectedWorkout = w; workoutMenuExpanded = false },
                        )
                    }
                }
            }
            selectedWorkout?.let { w ->
                Spacer(Modifier.height(12.dp))
                WorkoutPreview(workout = w, ftp = ftp)
            }
        }

        // Ride the route with its gradient flattened (no climb resistance / no
        // downhill free speed). Whole row toggles, text centred on the checkbox.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { ignoreHills = !ignoreHills },
        ) {
            Checkbox(checked = ignoreHills, onCheckedChange = { ignoreHills = it })
            Text("Ride as flat", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(4.dp))
        val rideLabel = when {
            randomSelected -> "  Start ride"
            selectedStats != null -> {
                val (km, gain) = selectedStats!!
                "  Start ride (${String.format(java.util.Locale.US, "%.1f", km)} km · +${gain.toInt()} m)"
            }
            else -> "  Start ride"
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = randomSelected || selectedGpx != null,
            onClick = {
                if (randomSelected) {
                    startRide(RouteGenerator.generate(difficulty = settings.difficulty), 0.0, ignoreHills)
                } else {
                    val file = selectedGpx ?: return@Button
                    val startMeters = (startKm.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0) * 1000.0
                    loadAndStart(RouteLibrary.prettyName(file), file.nameWithoutExtension, startMeters, ignoreHills) {
                        java.io.FileInputStream(file)
                    }
                }
            },
        ) {
            Icon(Icons.Filled.DirectionsBike, contentDescription = null)
            Text(rideLabel, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }

    prefetchFor?.let { file ->
        PrefetchDialog(file = file, onDismiss = { prefetchFor = null })
    }
    stylizeFor?.let { route ->
        StylizeDialog(route = route, frames = svFrames, onDismiss = { remaining ->
            if (selectedRoute === route) missingTiles = remaining
            stylizeFor = null
        })
    }
}

/** Downloads the terrain tiles a route's stylized 3D scenery needs, for offline rides. */
@Composable
private fun StylizeDialog(route: Route, frames: StreetViewManifest?, onDismiss: (remaining: Int?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var total by remember { mutableStateOf<Int?>(null) }
    var missingPair by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Street View frames whose real camera position still needs a (free) lookup.
    var unlocated by remember { mutableStateOf(frames?.unlocatedCount ?: 0) }
    // Street View frames not yet turned into paintings.
    var unpainted by remember { mutableStateOf(0) }
    val missing = missingPair?.let { it.first + it.second + unlocated + unpainted }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TerrainTiles.Progress?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    suspend fun refresh() {
        val (t, m) = withContext(Dispatchers.IO) {
            TerrainTiles.totalTiles(route) to TerrainTiles.missing(context, route)
        }
        total = t
        missingPair = m
        unpainted = frames?.let { f -> withContext(Dispatchers.IO) { com.bike.trainer.ui.ride.PhotoFrames.unpainted(context, f).size } } ?: 0
    }
    LaunchedEffect(route) { refresh() }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss(missing) },
        title = { Text(if (running) "Stylizing…" else "Stylize route") },
        text = {
            Column {
                Text(
                    "Builds a 3D model of ${route.name} from real terrain and OpenStreetMap " +
                        "(buildings, streets, parks, forests, water), then paints your prefetched " +
                        "Street View photos onto it as an illustration. Downloads the map data once " +
                        "(free, no key) so the ride works offline.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                val t = total
                val m = missing
                if (t == null || m == null) {
                    Text("Checking…", style = MaterialTheme.typography.bodySmall)
                } else if (m == 0) {
                    Text(
                        "Ready — all $t map tiles are on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    val tiles = missingPair!!.first + missingPair!!.second
                    if (tiles > 0) {
                        val mb = TerrainTiles.estimateBytes(missingPair!!) / 1_000_000.0
                        Text(
                            "$tiles of $t tiles to download (≈${String.format(java.util.Locale.US, "%.0f", mb.coerceAtLeast(1.0))} MB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (unlocated > 0) {
                        Text(
                            "$unlocated Street View photos to align with the 3D model (free lookups)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (unpainted > 0) {
                        Text(
                            "$unpainted Street View photos to paint (done on the phone, no download)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val p = progress
                if (p != null && running) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Working ${p.done}/${p.total}", style = MaterialTheme.typography.bodySmall)
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && (missing ?: 0) > 0,
                onClick = {
                    running = true
                    status = null
                    job = scope.launch {
                        val failed = TerrainTiles.stylize(context, route) { progress = it }
                        if (unlocated > 0 && com.bike.trainer.BuildConfig.HAS_MAPS_KEY) {
                            unlocated = StreetViewPrefetcher.locate(
                                context, route, com.bike.trainer.BuildConfig.MAPS_API_KEY,
                            ) { done, total -> progress = TerrainTiles.Progress(done, total, 0) }
                        }
                        // Paint every photo now so the ride never waits for it.
                        frames?.let { f ->
                            val todo = withContext(Dispatchers.IO) { com.bike.trainer.ui.ride.PhotoFrames.unpainted(context, f) }
                            todo.forEachIndexed { i, file ->
                                if (!isActive) return@forEachIndexed
                                withContext(Dispatchers.Default) { com.bike.trainer.ui.ride.PhotoFrames.painted(file)?.recycle() }
                                progress = TerrainTiles.Progress(i + 1, todo.size, 0)
                            }
                        }
                        running = false
                        refresh()
                        status = if (failed == 0) "Done — this route now rides offline"
                        else "$failed tiles failed — check your connection and retry"
                    }
                },
            ) { Text("Stylize") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (running) {
                    job?.cancel()
                    running = false
                    status = "Cancelled"
                    scope.launch { refresh() }
                } else {
                    onDismiss(missing)
                }
            }) { Text(if (running) "Cancel" else "Close") }
        },
    )
}

/** Sampling/cost dialog that prefetches Street View frames for one GPX route. */
@Composable
private fun PrefetchDialog(file: java.io.File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routeId = file.nameWithoutExtension

    var route by remember { mutableStateOf<Route?>(null) }
    var spacing by remember { mutableStateOf(20f) }
    var reuse by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<StreetViewPrefetcher.Progress?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val existing = remember(routeId) { StreetViewCache.load(context, routeId) }

    LaunchedEffect(file) {
        route = withContext(Dispatchers.IO) {
            runCatching { file.inputStream().use { GpxImporter.import(routeId, it, routeId) } }.getOrNull()
        }
    }

    val total = route?.totalDistance ?: 0.0
    val count = StreetViewPrefetcher.sampleCount(total, spacing.toDouble())
    val cost = count * StreetViewPrefetcher.USD_PER_IMAGE

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(if (running) "Prefetching Street View…" else "Prefetch Street View") },
        text = {
            Column {
                if (route == null) {
                    Text("Reading route…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "${RouteLibrary.prettyName(file)} • ${String.format(java.util.Locale.US, "%.1f", total / 1000.0)} km",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (existing != null) {
                        val r = route
                        val stale = r != null && existing.routeFingerprint.isNotEmpty() &&
                            existing.routeFingerprint != StreetViewCache.fingerprint(r)
                        if (stale) {
                            Text(
                                "Cached copy is out of date for this GPX — re-fetch recommended",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(
                                "Already cached: ${existing.imageCount} frames at ${existing.spacingMeters.toInt()} m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Sample every ${spacing.toInt()} m", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = spacing,
                        onValueChange = { spacing = it },
                        valueRange = 5f..60f,
                        steps = 10,
                        enabled = !running,
                    )
                    Text(
                        "≈ $count images  •  ≈ \$${String.format(java.util.Locale.US, "%.2f", cost)} on your Google key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (existing != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = reuse, onCheckedChange = { reuse = it }, enabled = !running)
                            Text(
                                "Reuse already-downloaded frames",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    val p = progress
                    if (p != null) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Downloading ${p.done}/${p.total} • ${p.withImage} with imagery",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    status?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = route != null && !running,
                onClick = {
                    val r = route ?: return@TextButton
                    running = true
                    status = null
                    progress = null
                    job = scope.launch {
                        val result = StreetViewPrefetcher.prefetch(
                            context, r, routeId, spacing.toDouble(),
                            com.bike.trainer.BuildConfig.MAPS_API_KEY,
                            reuseExisting = reuse,
                        ) { progress = it }
                        running = false
                        status = when (result) {
                            is StreetViewPrefetcher.Result.Success -> {
                                val imgs = result.manifest.imageCount
                                val depth = if (result.depthCount > 0) {
                                    " • depth ${result.depthCount}/$imgs"
                                } else " • no depth available"
                                "Done — $imgs frames cached$depth"
                            }
                            is StreetViewPrefetcher.Result.Error -> result.message
                        }
                    }
                },
            ) { Text(if (existing != null) "Re-fetch" else "Download") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (running) {
                    job?.cancel()
                    running = false
                    status = "Cancelled"
                } else {
                    onDismiss()
                }
            }) { Text(if (running) "Cancel" else "Close") }
        },
    )
}

/** Resolve a content Uri's display name (without extension) for the route title. */
private fun displayName(context: Context, uri: Uri): String? {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
    return name?.substringBeforeLast('.')?.ifBlank { null }
}

/** Dropdown label: "Freeride" or "Name (45 min · 3/5)". */
private fun workoutLabel(w: Workout?): String =
    if (w == null) "Freeride"
    else "${w.name} (${(w.totalSeconds + 30) / 60} min · ${w.difficulty}/5)"

/** A power-profile preview; tap a bar to see that step's exact power + duration. */
@Composable
private fun WorkoutPreview(workout: Workout, ftp: Int) {
    var selected by remember(workout) { mutableStateOf<Int?>(null) }
    val maxFrac = (workout.steps.maxOfOrNull { it.ftpFraction } ?: 1.0).coerceAtLeast(1.0)
    val total = workout.totalSeconds.toFloat().coerceAtLeast(1f)
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(workout) {
                    detectTapGestures { off ->
                        val frac = (off.x / size.width).coerceIn(0f, 1f)
                        var acc = 0
                        var idx = workout.steps.lastIndex
                        for (i in workout.steps.indices) {
                            acc += workout.steps[i].seconds
                            if (frac <= acc / total) { idx = i; break }
                        }
                        selected = if (selected == idx) null else idx
                    }
                },
        ) {
            var x = 0f
            workout.steps.forEachIndexed { i, s ->
                val bw = (s.seconds / total) * size.width
                val bh = (s.ftpFraction / maxFrac).toFloat() * size.height
                val base = workoutZoneColor(s.ftpFraction)
                drawRect(
                    color = if (selected == null || selected == i) base else base.copy(alpha = 0.55f),
                    topLeft = Offset(x, size.height - bh),
                    size = Size(bw.coerceAtLeast(1f), bh),
                )
                if (selected == i) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(x, size.height - bh),
                        size = Size(bw.coerceAtLeast(1f), bh),
                        style = Stroke(width = 2f),
                    )
                }
                x += bw
            }
        }
        Spacer(Modifier.height(6.dp))
        val sel = selected?.let { workout.steps[it] }
        if (sel != null) {
            Text(
                "Step ${selected!! + 1}: ${(sel.ftpFraction * ftp).toInt()} W " +
                    "(${(sel.ftpFraction * 100).toInt()}% FTP) for ${fmtDur(sel.seconds)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            val lo = (workout.steps.minOf { it.ftpFraction } * ftp).toInt()
            val hi = (workout.steps.maxOf { it.ftpFraction } * ftp).toInt()
            Text(
                "${(workout.totalSeconds + 30) / 60} min · difficulty ${workout.difficulty}/5 · " +
                    "$lo–$hi W (at ${ftp}W FTP) · tap a bar for details",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** mm:ss for a step duration. */
private fun fmtDur(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)
