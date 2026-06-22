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
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.bike.trainer.session.RideEngine
import com.bike.trainer.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun loadAndStart(name: String, open: () -> java.io.InputStream?) {
        scope.launch {
            val route = withContext(Dispatchers.IO) {
                runCatching { open()?.use { GpxImporter.import(name, it) } }.getOrNull()
            }
            if (route != null) startRide(route)
            else Toast.makeText(context, "Couldn't read that GPX route", Toast.LENGTH_LONG).show()
        }
    }

    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            loadAndStart(displayName(context, uri) ?: "Imported route") {
                context.contentResolver.openInputStream(uri)
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
            Text("Or ride a real route", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { gpxPicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Text("  Import GPX")
                }
                OutlinedButton(onClick = {
                    loadAndStart("Paris–Roubaix (example)") {
                        context.assets.open("routes/paris_roubaix.gpx")
                    }
                }) {
                    Icon(Icons.Filled.Route, contentDescription = null)
                    Text("  Paris–Roubaix")
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
}

/** Resolve a content Uri's display name (without extension) for the route title. */
private fun displayName(context: Context, uri: Uri): String? {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
    return name?.substringBeforeLast('.')?.ifBlank { null }
}
