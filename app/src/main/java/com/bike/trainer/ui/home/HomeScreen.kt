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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.bike.trainer.ble.TrainerConnectionState
import com.bike.trainer.ble.TrainerControlMode
import com.bike.trainer.data.AppConfig
import com.bike.trainer.data.ProfileEntry
import com.bike.trainer.data.ProfilesState
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
    onConnectTrainer: () -> Unit,
    onConnectHeartRate: () -> Unit,
    onConnectController: () -> Unit,
    onViewStats: () -> Unit,
    onStartRide: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trainer = ServiceLocator.trainerConnection
    val hrm = ServiceLocator.heartRateManager
    val controller = ServiceLocator.zwiftClickManager
    val settingsRepo = ServiceLocator.settingsRepository
    val configRepo = ServiceLocator.appConfigRepository
    val stravaRepo = ServiceLocator.stravaRepository
    val profileRepo = ServiceLocator.profileRepository

    val trainerState by trainer.connectionState.collectAsStateWithLifecycle()
    val controlMode by trainer.controlMode.collectAsStateWithLifecycle()
    val trainerName by trainer.connectedDeviceName.collectAsStateWithLifecycle()
    val hrState by hrm.connectionState.collectAsStateWithLifecycle()
    val hrName by hrm.connectedDeviceName.collectAsStateWithLifecycle()
    val controllerState by controller.connectionState.collectAsStateWithLifecycle()
    val controllerName by controller.connectedDeviceName.collectAsStateWithLifecycle()
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = RiderSettings())
    val config by configRepo.config.collectAsStateWithLifecycle(initialValue = null)
    val stravaConnected by stravaRepo.isConnected.collectAsStateWithLifecycle(initialValue = false)
    val profiles by profileRepo.state.collectAsStateWithLifecycle(initialValue = ProfilesState())
    val activeProfile = profiles.active

    var showStravaDialog by remember { mutableStateOf(false) }
    var showMapDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var promptedForMap by remember { mutableStateOf(false) }

    // Ask for a MapTiler key once if none has been provided yet.
    LaunchedEffect(config) {
        val cfg = config
        if (cfg != null && !cfg.mapConfigured && !promptedForMap) {
            promptedForMap = true
            showMapDialog = true
        }
    }

    val startRide: (Route) -> Unit = { route ->
        ServiceLocator.activeRide = RideEngine(
            route = route,
            trainer = trainer,
            riderMassKg = activeProfile?.profile?.weightKg ?: 75.0,
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
            Icon(Icons.Filled.DirectionsBike, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Bike", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        // ---- Devices ----
        SectionCard {
            Text("Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            val trainerStatus = when (trainerState) {
                TrainerConnectionState.Connected -> {
                    val mode = if (controlMode == TrainerControlMode.Simulation) "resistance" else "power only"
                    "${trainerName ?: "trainer"} · $mode"
                }
                TrainerConnectionState.Connecting -> "Connecting…"
                TrainerConnectionState.Scanning -> "Scanning…"
                else -> "Not connected"
            }
            DeviceRow(Icons.Filled.Bluetooth, "Trainer", trainerStatus,
                connected = trainerState == TrainerConnectionState.Connected, onClick = onConnectTrainer)

            DeviceRow(Icons.Filled.Favorite, "Heart rate",
                if (hrState == TrainerConnectionState.Connected) (hrName ?: "connected") else "Not connected",
                connected = hrState == TrainerConnectionState.Connected, onClick = onConnectHeartRate)

            DeviceRow(Icons.Filled.Gamepad, "Gear controller",
                if (controllerState == TrainerConnectionState.Connected) (controllerName ?: "connected") else "Not connected",
                connected = controllerState == TrainerConnectionState.Connected, onClick = onConnectController)
        }

        // ---- Route ----
        SectionCard {
            Text("Route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Random corridor difficulty", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        // ---- Rider profile ----
        SectionCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Rider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onViewStats) { Text("Stats") }
                    TextButton(onClick = { showProfileDialog = true }) { Text("Switch") }
                }
            }
            val weight = activeProfile?.profile?.weightKg ?: 75.0
            Text(
                "${activeProfile?.profile?.name ?: "Rider"} · ${weight.toInt()} kg",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Slider(
                value = weight.toFloat(),
                onValueChange = { v ->
                    scope.launch { profileRepo.updateActiveProfile { it.copy(weightKg = v.toDouble()) } }
                },
                valueRange = 40f..130f,
            )
            Text("Weight is used to model how fast you climb for a given power.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- 3D map (MapTiler key) ----
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("3D map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            val mapReady = config?.mapConfigured == true
            Text(
                if (mapReady) "MapTiler key set — full 3D terrain, satellite & buildings."
                else "No MapTiler key — riding on the flat demo map. Add a free key for 3D scenery.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showMapDialog = true }) {
                Text(if (mapReady) "Change MapTiler key" else "Add MapTiler key")
            }
        }

        // ---- Strava ----
        SectionCard {
            Text("Strava", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val stravaConfigured = config?.stravaConfigured == true
            when {
                stravaConnected -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text("  Connected — rides can be uploaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { stravaRepo.disconnect() } }) { Text("Disconnect") }
                        OutlinedButton(onClick = { showStravaDialog = true }) { Text("Edit keys") }
                    }
                }
                stravaConfigured -> {
                    Text("Connect your account to upload finished rides.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("Add your Strava API keys to enable uploads.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showStravaDialog = true }) { Text("Add Strava keys") }
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
            initial = config ?: AppConfig("", "", ""),
            onDismiss = { showStravaDialog = false },
            onSave = { id, secret ->
                scope.launch { configRepo.setStravaCredentials(id, secret) }
                showStravaDialog = false
            },
        )
    }
    if (showProfileDialog) {
        ProfileDialog(
            profiles = profiles,
            onDismiss = { showProfileDialog = false },
            onSelect = { id ->
                scope.launch { profileRepo.setActive(id) }
                showProfileDialog = false
            },
            onAdd = { name, weight ->
                scope.launch { profileRepo.addProfile(name, weight) }
                showProfileDialog = false
            },
        )
    }
}

@Composable
private fun ProfileDialog(
    profiles: ProfilesState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: (String, Double) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newWeight by remember { mutableStateOf("75") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Riders") },
        text = {
            Column {
                profiles.entries.forEach { entry ->
                    val isActive = entry.profile.id == profiles.activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${entry.profile.name} · ${entry.profile.weightKg.toInt()} kg",
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        )
                        TextButton(onClick = { onSelect(entry.profile.id) }) {
                            Text(if (isActive) "Active" else "Select")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Add a rider", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = newName, onValueChange = { newName = it },
                    label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = newWeight, onValueChange = { newWeight = it.filter { c -> c.isDigit() } },
                    label = { Text("Weight (kg)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(newName, newWeight.toDoubleOrNull() ?: 75.0) },
                enabled = newName.isNotBlank(),
            ) { Text("Add rider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DeviceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    status: String,
    connected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, contentDescription = null,
                tint = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(name, fontWeight = FontWeight.Medium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onClick) { Text(if (connected) "Manage" else "Connect") }
    }
}

@Composable
private fun KeyDialog(
    title: String,
    label: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StravaKeysDialog(
    initial: AppConfig,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var id by remember { mutableStateOf(initial.stravaClientId) }
    var secret by remember { mutableStateOf(initial.stravaClientSecret) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Strava API keys") },
        text = {
            Column {
                Text(
                    "Create an app at strava.com/settings/api and set the Authorization " +
                        "Callback Domain to: strava-auth",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Client ID") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text("Client Secret") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(id, secret) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
