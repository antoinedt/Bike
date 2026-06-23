package com.bike.trainer.ui.setup

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
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.ble.TrainerConnectionState
import com.bike.trainer.ble.TrainerControlMode
import com.bike.trainer.data.ProfilesState
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.ui.components.DeviceRow
import com.bike.trainer.ui.components.ProfileDialog
import com.bike.trainer.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    onConnectTrainer: () -> Unit,
    onConnectHeartRate: () -> Unit,
    onConnectController: () -> Unit,
    onOpenSettings: () -> Unit,
    onViewStats: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val trainer = ServiceLocator.trainerConnection
    val hrm = ServiceLocator.heartRateManager
    val controller = ServiceLocator.zwiftClickManager
    val profileRepo = ServiceLocator.profileRepository

    val trainerState by trainer.connectionState.collectAsStateWithLifecycle()
    val controlMode by trainer.controlMode.collectAsStateWithLifecycle()
    val trainerName by trainer.connectedDeviceName.collectAsStateWithLifecycle()
    val hrState by hrm.connectionState.collectAsStateWithLifecycle()
    val hrName by hrm.connectedDeviceName.collectAsStateWithLifecycle()
    val controllerState by controller.connectionState.collectAsStateWithLifecycle()
    val controllerName by controller.connectedDeviceName.collectAsStateWithLifecycle()
    // null = not loaded yet (don't confuse the loading placeholder with "no riders").
    val profilesState by profileRepo.state.collectAsStateWithLifecycle(initialValue = null)
    val profiles = profilesState ?: ProfilesState()
    val activeProfile = profiles.active

    var showProfileDialog by remember { mutableStateOf(false) }

    // Auto-open the rider picker only once profiles have actually loaded and there
    // are genuinely none (e.g. a fresh launch) — not on every return to this screen.
    LaunchedEffect(profilesState) {
        if (profilesState?.entries?.isEmpty() == true) showProfileDialog = true
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
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                Text("  Settings")
            }
        }

        // ---- Rider ----
        SectionCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Rider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row {
                    if (activeProfile != null) {
                        TextButton(onClick = onViewStats) { Text("Stats") }
                    }
                    TextButton(onClick = { showProfileDialog = true }) {
                        Text(if (activeProfile == null) "Create" else "Switch / add")
                    }
                }
            }
            if (activeProfile == null) {
                Text("No rider yet — create one to continue.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${activeProfile.profile.name} · ${activeProfile.profile.weightKg.toInt()} kg",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Change weight in Stats. It models how fast you climb for a given power.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Text("  Settings")
            }
            Button(onClick = onConfirm, enabled = activeProfile != null, modifier = Modifier.weight(1f)) {
                Text("Confirm", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(24.dp))
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
            onRemove = { id ->
                scope.launch { profileRepo.removeProfile(id) }
            },
        )
    }
}
