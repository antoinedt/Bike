package com.bike.trainer.ui.home

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.ble.TrainerConnectionState
import com.bike.trainer.ble.TrainerControlMode
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.physics.VirtualGears
import com.bike.trainer.route.GpxImporter
import com.bike.trainer.route.Route
import com.bike.trainer.route.RouteGenerator
import com.bike.trainer.session.RideEngine
import com.bike.trainer.strava.StravaConfig
import com.bike.trainer.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onConnectTrainer: () -> Unit,
    onStartRide: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trainer = ServiceLocator.trainerConnection
    val settingsRepo = ServiceLocator.settingsRepository
    val stravaRepo = ServiceLocator.stravaRepository

    val connectionState by trainer.connectionState.collectAsStateWithLifecycle()
    val controlMode by trainer.controlMode.collectAsStateWithLifecycle()
    val deviceName by trainer.connectedDeviceName.collectAsStateWithLifecycle()
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = com.bike.trainer.data.RiderSettings())
    val stravaConnected by stravaRepo.isConnected.collectAsStateWithLifecycle(initialValue = false)

    // Builds the engine for a chosen route and opens the ride screen.
    val startRide: (Route) -> Unit = { route ->
        ServiceLocator.activeRide = RideEngine(
            route = route,
            trainer = trainer,
            riderMassKg = settings.riderMassKg,
            gears = VirtualGears(gearCount = settings.gearCount),
        )
        onStartRide()
    }

    // Loads a route on a background thread, then starts it (or reports an error).
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
            Icon(
                Icons.Filled.DirectionsBike,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Bike",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Virtual indoor rides with real resistance.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- Trainer connection ----
        SectionCard {
            Text("Trainer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val statusText = when (connectionState) {
                TrainerConnectionState.Connected -> {
                    val mode = if (controlMode == TrainerControlMode.Simulation) "resistance control" else "power only"
                    "Connected: ${deviceName ?: "trainer"} ($mode)"
                }
                TrainerConnectionState.Connecting -> "Connecting…"
                TrainerConnectionState.Scanning -> "Scanning…"
                TrainerConnectionState.Failed -> "Connection failed"
                else -> "Not connected"
            }
            Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onConnectTrainer) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null)
                Text(
                    if (connectionState == TrainerConnectionState.Connected) "  Manage trainer" else "  Connect trainer",
                )
            }
        }

        // ---- Route options ----
        SectionCard {
            Text("Route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Random corridor difficulty",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Text(
                "Or ride a real route",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { gpxPicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Text("  Import GPX")
                }
                OutlinedButton(
                    onClick = {
                        loadAndStart("Paris–Roubaix (example)") {
                            context.assets.open("routes/paris_roubaix.gpx")
                        }
                    },
                ) {
                    Icon(Icons.Filled.Route, contentDescription = null)
                    Text("  Paris–Roubaix")
                }
            }
        }

        // ---- Rider profile ----
        SectionCard {
            Text("Rider weight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${settings.riderMassKg.toInt()} kg",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Slider(
                value = settings.riderMassKg.toFloat(),
                onValueChange = { scope.launch { settingsRepo.setRiderMass(it.toDouble()) } },
                valueRange = 40f..130f,
            )
            Text(
                "Used to model how fast you climb for a given power.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Strava ----
        SectionCard {
            Text("Strava", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (stravaConnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text("  Connected — rides can be uploaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { stravaRepo.disconnect() } }) {
                    Text("Disconnect")
                }
            } else {
                Text(
                    if (StravaConfig.isConfigured) "Connect to upload finished rides." else
                        "Add STRAVA_CLIENT_ID/SECRET at build time to enable uploads.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = StravaConfig.isConfigured,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(StravaConfig.authorizeUrl()))
                        context.startActivity(intent)
                    },
                ) {
                    Text("Connect Strava")
                }
            }
        }

        Spacer(Modifier.height(4.dp))

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
