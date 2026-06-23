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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.data.ATTACK_INTERVALS
import com.bike.trainer.data.CLIMB_INTERVALS
import com.bike.trainer.data.RECENT_WINDOW_MS
import com.bike.trainer.data.SPRINT_INTERVALS
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

        // ---- Rider: weight + FTP ----
        SectionCard {
            var weight by remember(entry?.profile?.weightKg) {
                mutableStateOf((entry?.profile?.weightKg ?: 75.0).toFloat())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Weight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${weight.toInt()} kg", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = weight,
                onValueChange = { weight = it },
                valueRange = 40f..130f,
                onValueChangeFinished = {
                    scope.launch {
                        ServiceLocator.profileRepository.updateActiveProfile { it.copy(weightKg = weight.toDouble()) }
                    }
                },
            )
            Text(
                "Used to model how fast you climb for a given power.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            var ftp by remember(entry?.profile?.ftpWatts) {
                mutableStateOf((entry?.profile?.ftpWatts ?: 200).toFloat())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FTP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${ftp.toInt()} W", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = ftp,
                onValueChange = { ftp = it },
                valueRange = 80f..400f,
                onValueChangeFinished = {
                    scope.launch {
                        ServiceLocator.profileRepository.updateActiveProfile { it.copy(ftpWatts = ftp.toInt()) }
                    }
                },
            )
            Text(
                "Functional Threshold Power — workout power targets scale from this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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

        Text("Power bests", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val allTime = stats?.bestAvgPowerW ?: emptyMap()
        val recent = remember(stats) {
            stats?.bestPowerSince(System.currentTimeMillis() - RECENT_WINDOW_MS) ?: emptyMap()
        }
        PowerSection("Sprint", SPRINT_INTERVALS, allTime, recent)
        PowerSection("Attack", ATTACK_INTERVALS, allTime, recent)
        PowerSection("Climb", CLIMB_INTERVALS, allTime, recent)

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
private fun PowerSection(
    title: String,
    intervals: List<Int>,
    allTime: Map<Int, Int>,
    recent: Map<Int, Int>,
) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        // Column headers
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(
                "",
                modifier = Modifier.weight(1.2f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "All-time",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
            )
            Text(
                "90 days",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
            )
        }
        intervals.forEach { w ->
            BestPowerRow(
                label = intervalLabel(w),
                allTime = allTime[w]?.let { "$it W" },
                recent = recent[w]?.let { "$it W" },
            )
        }
    }
}

@Composable
private fun BestPowerRow(label: String, allTime: String?, recent: String?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1.2f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            allTime ?: "—",
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
        Text(
            recent ?: "—",
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
        )
    }
}

private fun formatHours(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
