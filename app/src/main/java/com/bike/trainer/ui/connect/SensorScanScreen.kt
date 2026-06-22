package com.bike.trainer.ui.connect

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.ble.BleSensor
import com.bike.trainer.ble.TrainerConnectionState

/**
 * Generic scan/connect UI for any [BleSensor] — heart-rate straps, gear
 * controllers, etc. Requests the Bluetooth runtime permissions, scans, and lets
 * the user tap a discovered device to connect.
 */
@Composable
fun SensorScanScreen(
    title: String,
    subtitle: String,
    sensor: BleSensor,
    onBack: () -> Unit,
) {
    val connectionState by sensor.connectionState.collectAsStateWithLifecycle()
    val discovered by sensor.discovered.collectAsStateWithLifecycle()
    val connectedName by sensor.connectedDeviceName.collectAsStateWithLifecycle()

    var permissionsGranted by remember { mutableStateOf(false) }
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (permissionsGranted) sensor.startScan()
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(requiredPermissions) }
    DisposableEffect(Unit) { onDispose { sensor.stopScan() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            Text("  Back")
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            when (connectionState) {
                TrainerConnectionState.Connected -> "Connected: ${connectedName ?: "device"}"
                TrainerConnectionState.Connecting -> "Connecting…"
                TrainerConnectionState.Failed -> "Nothing found. Make sure the device is awake and nearby."
                else -> subtitle
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connectionState == TrainerConnectionState.Scanning ||
                connectionState == TrainerConnectionState.Connecting
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = {
                if (permissionsGranted) sensor.startScan() else permissionLauncher.launch(requiredPermissions)
            }) { Text("Rescan") }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(discovered, key = { it.address }) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sensor.connect(device.address) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                device.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${device.rssi} dBm", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (connectionState == TrainerConnectionState.Connected) {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}
