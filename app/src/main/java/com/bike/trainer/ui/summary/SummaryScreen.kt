package com.bike.trainer.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.export.LocalRideStore
import com.bike.trainer.export.TcxWriter
import com.bike.trainer.session.RideStatsCalculator
import com.bike.trainer.strava.UploadResult
import com.bike.trainer.ui.components.SectionCard
import com.bike.trainer.ui.components.StatTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private sealed interface UploadUiState {
    data object Idle : UploadUiState
    data object Uploading : UploadUiState
    data class Done(val result: UploadResult) : UploadUiState
}

@Composable
fun SummaryScreen(onDone: () -> Unit) {
    val engine = ServiceLocator.activeRide
    if (engine == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by engine.state.collectAsStateWithLifecycle()
    val stravaConnected by ServiceLocator.stravaRepository.isConnected.collectAsStateWithLifecycle(initialValue = false)

    var uploadState by remember { mutableStateOf<UploadUiState>(UploadUiState.Idle) }
    var savedFile by remember { mutableStateOf<File?>(null) }

    // On finish: save the TCX locally and fold the ride into the rider's stats.
    LaunchedEffect(Unit) {
        val points = engine.recorder.snapshot()
        savedFile = withContext(Dispatchers.IO) {
            runCatching {
                LocalRideStore.save(context, engine.route.name, TcxWriter.write(engine.route.name, points))
            }.getOrNull()
        }
        val summary = RideStatsCalculator.compute(points)
        runCatching { ServiceLocator.profileRepository.applyRideToActive(summary) }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val file = savedFile
        if (uri != null && file != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(file.readBytes()) }
                    }
                }
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
        Text("Ride complete", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(engine.route.name, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile("Distance", String.format(Locale.US, "%.2f km", state.distanceMeters / 1000.0), accent = true)
                StatTile("Time", formatTime(state.elapsedSeconds))
                StatTile("Ascent", "${state.totalAscentMeters.toInt()} m")
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile("Avg Power", "${state.avgPowerWatts} W")
                StatTile("Energy", "${state.energyKilojoules.toInt()} kJ")
                StatTile("Avg Speed", avgSpeed(state.distanceMeters, state.elapsedSeconds))
            }
        }

        SectionCard {
            Text("Saved ride file", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val file = savedFile
            if (file != null) {
                Text("Saved locally as ${file.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "In ${file.parentFile?.absolutePath ?: "app storage"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { exporter.launch(file.name) }) { Text("Export / Save as…") }
            } else {
                Text("Saving…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionCard {
            Text("Upload to Strava", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            when (val s = uploadState) {
                is UploadUiState.Uploading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                        Text("  Uploading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is UploadUiState.Done -> {
                    val msg = when (val r = s.result) {
                        is UploadResult.Success -> "Uploaded to Strava ✅"
                        is UploadResult.Error -> "Failed: ${r.message}"
                        UploadResult.NotAuthorized -> "Connect Strava from the home screen first."
                        UploadResult.NotConfigured -> "Strava credentials are not configured in this build."
                    }
                    Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                UploadUiState.Idle -> {
                    if (!stravaConnected) {
                        Text(
                            "Connect Strava from the home screen to enable uploads.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = stravaConnected,
                        onClick = {
                            uploadState = UploadUiState.Uploading
                            scope.launch {
                                val tcx = TcxWriter.write(engine.route.name, engine.recorder.snapshot())
                                val result = ServiceLocator.stravaRepository.uploadTcx(
                                    name = engine.route.name,
                                    description = "Virtual ride on Bike",
                                    tcx = tcx.toByteArray(Charsets.UTF_8),
                                )
                                uploadState = UploadUiState.Done(result)
                            }
                        },
                    ) {
                        Text("Upload ride")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                ServiceLocator.activeRide = null
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(),
        ) {
            Text("Back to home")
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun avgSpeed(distanceMeters: Double, elapsedSeconds: Long): String {
    if (elapsedSeconds <= 0) return "0.0 km/h"
    val kmh = (distanceMeters / 1000.0) / (elapsedSeconds / 3600.0)
    return String.format(Locale.US, "%.1f km/h", kmh)
}

private fun formatTime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
