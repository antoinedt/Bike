package com.bike.trainer.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.data.StravaAccount
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.ui.components.KeyDialog
import com.bike.trainer.ui.components.SectionCard
import com.bike.trainer.ui.components.StravaKeysDialog
import com.bike.trainer.ui.ride.SvMotion
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configRepo = ServiceLocator.appConfigRepository
    val stravaRepo = ServiceLocator.stravaRepository
    val profileRepo = ServiceLocator.profileRepository

    val config by configRepo.config.collectAsStateWithLifecycle(initialValue = null)
    val activeEntry by profileRepo.active.collectAsStateWithLifecycle(initialValue = null)
    val stravaConnected by stravaRepo.isConnected.collectAsStateWithLifecycle(initialValue = false)
    val stravaConfigured by stravaRepo.isConfigured.collectAsStateWithLifecycle(initialValue = false)

    var showMapDialog by remember { mutableStateOf(false) }
    var showStravaDialog by remember { mutableStateOf(false) }

    val backupExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        ServiceLocator.backupManager.exportZip(context, it)
                    }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "Backed up (settings + routes + Street View)" else "Backup failed", Toast.LENGTH_SHORT).show()
        }
    }
    val backupRestore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            val ok = bytes != null && withContext(Dispatchers.IO) {
                runCatching { ServiceLocator.backupManager.importAuto(context, bytes) }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "Restored" else "Couldn't restore that file", Toast.LENGTH_LONG).show()
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
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            Text("  Back")
        }
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // ---- 3D map key (global) ----
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("  3D map (MapTiler)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            val mapReady = config?.mapConfigured == true
            Text(
                if (mapReady) "MapTiler key set — full 3D terrain, satellite & buildings."
                else "No MapTiler key — riding on the flat demo map.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showMapDialog = true }) {
                Text(if (mapReady) "Change MapTiler key" else "Add MapTiler key")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Google Street View uses a build-time key (MAPS_API_KEY) and can't be set here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Street View motion (advanced) ----
        SectionCard {
            Text("Street View motion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "How prefetched Street View frames fake forward motion. Parallax — the " +
                    "ground-plane illusion where the near road rushes faster than the " +
                    "horizon — is the default. You can still switch styles live while riding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            val currentMode = remember(config?.svMotionMode) {
                runCatching { SvMotion.valueOf(config?.svMotionMode ?: "PARALLAX") }
                    .getOrNull() ?: SvMotion.PARALLAX
            }
            var modeMenu by remember { mutableStateOf(false) }
            Text("Default style", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Box {
                OutlinedButton(onClick = { modeMenu = true }) { Text(currentMode.label) }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    SvMotion.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label) },
                            onClick = {
                                scope.launch { configRepo.setSvMotionMode(m.name) }
                                modeMenu = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            MotionSlider("Strength", config?.svStrength ?: 0.16f, 0f, 0.5f) {
                scope.launch { configRepo.setSvStrength(it) }
            }
            MotionSlider("Horizon height", config?.svHorizon ?: 0.45f, 0.25f, 0.65f) {
                scope.launch { configRepo.setSvHorizon(it) }
            }
            MotionSlider("Ground rush (Parallax)", config?.svGroundRush ?: 1.5f, 0f, 3f) {
                scope.launch { configRepo.setSvGroundRush(it) }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                scope.launch {
                    configRepo.setSvMotionMode("PARALLAX")
                    configRepo.setSvStrength(0.16f)
                    configRepo.setSvHorizon(0.45f)
                    configRepo.setSvGroundRush(1.5f)
                }
            }) { Text("Reset to defaults") }
        }

        // ---- Strava (per rider) ----
        SectionCard {
            Text(
                "Strava — ${activeEntry?.profile?.name ?: "rider"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            when {
                stravaConnected -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text("  Connected — rides upload to this rider's Strava", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { stravaRepo.disconnect() } }) { Text("Disconnect") }
                        OutlinedButton(onClick = { showStravaDialog = true }) { Text("Edit keys") }
                    }
                }
                stravaConfigured -> {
                    Text("Connect this rider's Strava account to upload rides.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                stravaRepo.authorizeUrl()?.let {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                                }
                            }
                        }) { Text("Connect Strava") }
                        OutlinedButton(onClick = { showStravaDialog = true }) { Text("Edit keys") }
                    }
                }
                else -> {
                    Text("Add this rider's Strava API keys to enable uploads.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showStravaDialog = true }) { Text("Add Strava keys") }
                }
            }
        }

        // ---- Backup ----
        SectionCard {
            Text("Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Save riders, stats, keys, your GPX routes and prefetched Street View " +
                    "frames to one .zip (pick Google Drive in the save dialog), then " +
                    "restore after an update or reinstall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backupExport.launch("bike-backup.zip") }) { Text("Back up") }
                OutlinedButton(onClick = { backupRestore.launch(arrayOf("application/zip", "application/json", "*/*")) }) { Text("Restore") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showMapDialog) {
        KeyDialog(
            title = "MapTiler key",
            label = "MapTiler API key",
            hint = "Create a free key at maptiler.com → Account → Keys.",
            initial = config?.mapTilesKey.orEmpty(),
            onDismiss = { showMapDialog = false },
            onSave = { key ->
                scope.launch { configRepo.setMapTilesKey(key) }
                showMapDialog = false
            },
        )
    }
    if (showStravaDialog) {
        StravaKeysDialog(
            initial = activeEntry?.strava ?: StravaAccount(),
            onDismiss = { showStravaDialog = false },
            onSave = { id, secret ->
                scope.launch { profileRepo.setActiveStravaCredentials(id, secret) }
                showStravaDialog = false
            },
        )
    }
}

/**
 * Labelled slider whose live value is shown on the right. Drags update a local
 * copy; [onCommit] persists only when the drag ends (so we don't spam DataStore).
 * Re-seeds from [value] whenever the stored value changes.
 */
@Composable
private fun MotionSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onCommit: (Float) -> Unit,
) {
    var v by remember(value) { mutableStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                String.format(Locale.US, "%.2f", v),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            valueRange = min..max,
            onValueChangeFinished = { onCommit(v) },
        )
    }
}
