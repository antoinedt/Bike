package com.bike.trainer.ui.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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

    val startRide: (Route) -> Unit = { route ->
        ServiceLocator.activeRide = RideEngine(
            route = route,
            trainer = trainer,
            riderMassKg = activeEntry?.profile?.weightKg ?: 75.0,
            gears = VirtualGears(gearCount = settings.gearCount),
            heartRateManager = hrm,
        )
        onStartRide()
    }

    fun loadAndStart(name: String, id: String?, open: () -> java.io.InputStream?) {
        scope.launch {
            val route = withContext(Dispatchers.IO) {
                runCatching { open()?.use { GpxImporter.import(name, it, id) } }.getOrNull()
            }
            if (route != null) startRide(route)
            else Toast.makeText(context, "Couldn't read that GPX route", Toast.LENGTH_LONG).show()
        }
    }

    var gpxFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var selectedGpx by remember { mutableStateOf<java.io.File?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var prefetchFor by remember { mutableStateOf<java.io.File?>(null) }

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
            Text("Random corridor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteGenerator.Difficulty.entries.forEach { diff ->
                    FilterChip(
                        selected = settings.difficulty == diff,
                        onClick = { scope.launch { settingsRepo.setDifficulty(diff) } },
                        label = { Text(diff.label) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Or ride a GPX route", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            if (gpxFiles.isEmpty()) {
                Text(
                    "No GPX files yet. Add one below, or drop .gpx files into the app's routes folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = !menuExpanded },
                ) {
                    OutlinedTextField(
                        value = selectedGpx?.let { RouteLibrary.prettyName(it) } ?: "Select a route",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Saved route") },
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
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = selectedGpx != null,
                    onClick = {
                        val file = selectedGpx ?: return@Button
                        loadAndStart(RouteLibrary.prettyName(file), file.nameWithoutExtension) {
                            java.io.FileInputStream(file)
                        }
                    },
                ) {
                    Icon(Icons.Filled.Route, contentDescription = null)
                    Text("  Ride route")
                }
                OutlinedButton(onClick = { gpxPicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Text("  Add GPX")
                }
            }
            // Prefetch Google Street View frames along the selected route so the
            // ride plays them smoothly and offline (needs a Google Maps key).
            if (selectedGpx != null && com.bike.trainer.BuildConfig.HAS_MAPS_KEY) {
                TextButton(onClick = { prefetchFor = selectedGpx }) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Text("  Prefetch Street View…")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { startRide(RouteGenerator.generate(difficulty = settings.difficulty)) },
        ) {
            Icon(Icons.Filled.DirectionsBike, contentDescription = null)
            Text("  Start Random Ride", fontWeight = FontWeight.Bold)
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
                        Text(
                            "Already cached: ${existing.imageCount} frames at ${existing.spacingMeters.toInt()} m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                        ) { progress = it }
                        running = false
                        status = when (result) {
                            is StreetViewPrefetcher.Result.Success ->
                                "Done — ${result.manifest.imageCount} frames cached"
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
