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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.bike.trainer.ble.TrainerConnectionState
import com.bike.trainer.data.StravaAccount
import com.bike.trainer.garmin.GarminLoginResult
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.ui.components.KeyDialog
import com.bike.trainer.ui.components.SectionCard
import com.bike.trainer.ui.components.StravaKeysDialog
import com.bike.trainer.ui.ride.SvMotion
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val controllerState by ServiceLocator.zwiftClickManager.connectionState.collectAsStateWithLifecycle()
    val garminConnected by ServiceLocator.garminRepository.isConnected.collectAsStateWithLifecycle(initialValue = false)

    var showGarminDialog by remember { mutableStateOf(false) }
    var showMapDialog by remember { mutableStateOf(false) }
    var showStravaDialog by remember { mutableStateOf(false) }
    // Gear-controller button learning: which action ("up"/"down") is currently
    // listening for a press, and the last confirmation message.
    var learning by remember { mutableStateOf<String?>(null) }
    var learnMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(learning) {
        val which = learning ?: return@LaunchedEffect
        // Suspends until the controller reports the next button press.
        val field = ServiceLocator.zwiftClickManager.buttonPresses.first()
        val cfg = configRepo.current()
        val up = if (which == "up") field else cfg.gearUpField
        val down = if (which == "down") field else cfg.gearDownField
        configRepo.setGearButtonMapping(up, down)
        learnMsg = "Mapped gear ${if (which == "up") "up" else "down"} to button #$field"
        learning = null
    }

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
            // Stream straight from the file so a large backup doesn't load fully
            // into memory; surface the real reason if it fails.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        ServiceLocator.backupManager.importAuto(context, it)
                    } ?: throw IllegalStateException("Couldn't open the selected file")
                }
            }
            val msg = if (result.getOrDefault(false)) {
                "Restored"
            } else {
                "Couldn't restore: ${result.exceptionOrNull()?.message ?: "invalid backup file"}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
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

        // ---- In-ride control buttons ----
        SectionCard {
            Text("Ride controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Show or hide the on-screen buttons in the bottom panel while riding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow("Motion / smooth control", config?.showMotionControl ?: true) {
                scope.launch { configRepo.setShowMotionControl(it) }
            }
            ToggleRow("Camera view toggle", config?.showViewToggle ?: true) {
                scope.launch { configRepo.setShowViewToggle(it) }
            }
            ToggleRow("Photo capture button", config?.showCaptureButton ?: true) {
                scope.launch { configRepo.setShowCaptureButton(it) }
            }
        }

        // ---- Gear controller buttons ----
        SectionCard {
            Text("Gear controller buttons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val connected = controllerState == TrainerConnectionState.Connected
            if (!connected) {
                Text(
                    "Connect your gear controller on the Setup screen first, then come " +
                        "back here to map its buttons.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Tap a button below, then press the controller button you want to " +
                        "use for it. We'll remember it for your rides.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = learning == null, onClick = { learnMsg = null; learning = "up" }) {
                        Text(if (learning == "up") "Press now…" else "Set gear up")
                    }
                    OutlinedButton(enabled = learning == null, onClick = { learnMsg = null; learning = "down" }) {
                        Text(if (learning == "down") "Press now…" else "Set gear down")
                    }
                }
                learnMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Current — up: button #${config?.gearUpField ?: 1}, down: button #${config?.gearDownField ?: 2}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Workout ----
        SectionCard {
            Text("Workout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val tol = config?.workoutTolerance ?: 0.10f
            var tolLocal by remember(tol) { mutableStateOf(tol) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Power target tolerance", style = MaterialTheme.typography.labelMedium)
                Text("± ${(tolLocal * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = tolLocal,
                onValueChange = { tolLocal = it },
                valueRange = 0.02f..0.30f,
                onValueChangeFinished = { scope.launch { configRepo.setWorkoutTolerance(tolLocal) } },
            )
            Text(
                "How far from a workout's target power still counts as on-target " +
                    "(green). Below the band shows red, above shows purple.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

        // ---- Garmin Connect (auto-upload) ----
        SectionCard {
            Text("Garmin Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (garminConnected) {
                Text(
                    "Connected — finished rides upload to Garmin Connect (which can then " +
                        "sync to Strava, avoiding a duplicate).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { ServiceLocator.garminRepository.disconnect() } }) {
                    Text("Disconnect")
                }
            } else {
                Text(
                    "Auto-upload rides to Garmin. Garmin has no official upload API, so this " +
                        "uses your Garmin login (stored only on this device as a token). It can " +
                        "break when Garmin changes their site.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showGarminDialog = true }) { Text("Connect Garmin") }
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
    if (showGarminDialog) {
        GarminLoginDialog(onDismiss = { showGarminDialog = false })
    }
}

/** Garmin login (email + password, then a 2FA code if the account requires it). */
@Composable
private fun GarminLoginDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mfa by remember { mutableStateOf("") }
    var needMfa by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connect Garmin") },
        text = {
            Column {
                Text(
                    "Your Garmin login is used once to get a token kept only on this device. " +
                        "Unofficial — may need re-connecting if Garmin changes their site.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Garmin email") }, singleLine = true, enabled = !needMfa && !busy,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true, enabled = !needMfa && !busy,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (needMfa) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = mfa, onValueChange = { mfa = it },
                        label = { Text("2FA code") }, singleLine = true, enabled = !busy,
                    )
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (if (needMfa) mfa.isNotBlank() else email.isNotBlank() && password.isNotBlank()),
                onClick = {
                    busy = true
                    status = "Connecting…"
                    scope.launch {
                        val res = if (needMfa) ServiceLocator.garminRepository.submitMfa(mfa)
                            else ServiceLocator.garminRepository.beginLogin(email, password)
                        busy = false
                        when (res) {
                            is GarminLoginResult.Success -> {
                                Toast.makeText(context, "Connected to Garmin", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            is GarminLoginResult.MfaRequired -> {
                                needMfa = true
                                status = "Enter the 2FA code Garmin just sent you."
                            }
                            is GarminLoginResult.Error -> status = res.message
                        }
                    }
                },
            ) { Text(if (needMfa) "Verify" else "Connect") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Labelled slider whose live value is shown on the right. Drags update a local
 * copy; [onCommit] persists only when the drag ends (so we don't spam DataStore).
 * Re-seeds from [value] whenever the stored value changes.
 */
/** A label with a trailing on/off switch. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

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
