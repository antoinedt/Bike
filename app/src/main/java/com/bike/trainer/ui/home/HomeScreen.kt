package com.bike.trainer.ui.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.data.RiderSettings
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.physics.VirtualGears
import com.bike.trainer.route.GpxImporter
import com.bike.trainer.route.Route
import com.bike.trainer.route.RouteGenerator
import com.bike.trainer.route.RouteLibrary
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.route.StreetViewPrefetcher
import com.bike.trainer.session.RideEngine
import com.bike.trainer.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
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

    val startRide: (Route, Double, Boolean) -> Unit = { route, startMeters, ignoreHills ->
        ServiceLocator.activeRide = RideEngine(
            route = route,
            trainer = trainer,
            riderMassKg = activeEntry?.profile?.weightKg ?: 75.0,
            gears = VirtualGears(gearCount = settings.gearCount),
            heartRateManager = hrm,
            startDistanceMeters = startMeters,
            ignoreHills = ignoreHills,
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
    var startKm by remember { mutableStateOf("") }
    var ignoreHills by remember { mutableStateOf(false) }
    // (km, total ascent m) of the selected GPX, shown on the Start button.
    var selectedStats by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    LaunchedEffect(selectedGpx, randomSelected) {
        selectedStats = null
        val file = selectedGpx
        if (file != null && !randomSelected) {
            val route = withContext(Dispatchers.IO) {
                runCatching {
                    file.inputStream().use { GpxImporter.import(file.nameWithoutExtension, it, file.nameWithoutExtension) }
                }.getOrNull()
            }
            if (route != null && file == selectedGpx) {
                selectedStats = route.totalDistance / 1000.0 to route.totalAscent
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
