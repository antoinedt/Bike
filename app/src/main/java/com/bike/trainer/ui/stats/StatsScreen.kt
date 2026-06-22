package com.bike.trainer.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.data.STANDARD_INTERVALS
import com.bike.trainer.data.intervalLabel
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.ui.components.SectionCard
import com.bike.trainer.ui.components.StatTile
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val entry by ServiceLocator.profileRepository.active.collectAsStateWithLifecycle(initialValue = null)
    val stats = entry?.stats
    val scope = rememberCoroutineScope()
    var showReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            Text("  Back")
        }
        Text("Progression", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(entry?.profile?.name ?: "Rider", color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard {
            Text("Lifetime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile("Distance", String.format(Locale.US, "%.0f km", (stats?.totalDistanceMeters ?: 0.0) / 1000.0), accent = true)
                StatTile("Rides", "${stats?.totalRides ?: 0}")
                StatTile("Climb", "${(stats?.totalAscentMeters ?: 0.0).toInt()} m")
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile("Time", formatHours(stats?.totalTimeSeconds ?: 0))
                StatTile("Top speed", String.format(Locale.US, "%.1f km/h", stats?.topSpeedKmh ?: 0.0))
            }
        }

        SectionCard {
            Text("Best average speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            STANDARD_INTERVALS.forEach { w ->
                BestRow(intervalLabel(w), stats?.bestAvgSpeedKmh?.get(w)?.let {
                    String.format(Locale.US, "%.1f km/h", it)
                })
            }
        }

        SectionCard {
            Text("Best average power", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            STANDARD_INTERVALS.forEach { w ->
                BestRow(intervalLabel(w), stats?.bestAvgPowerW?.get(w)?.let { "$it W" })
            }
        }

        OutlinedButton(
            onClick = { showReset = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset all stats", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset all stats?") },
            text = {
                Text(
                    "This permanently clears ${entry?.profile?.name ?: "this rider"}'s lifetime " +
                        "distance, time, records and bests. It can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    scope.launch { ServiceLocator.profileRepository.resetActiveStats() }
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BestRow(label: String, value: String?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", fontWeight = FontWeight.SemiBold)
    }
}

private fun formatHours(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
