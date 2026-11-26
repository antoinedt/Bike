package com.medialibrary.manager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.cast.CastTarget
import com.medialibrary.manager.ui.MediaLibraryViewModel

/** The Cast button: highlighted while a TV is chosen. */
@Composable
fun CastButton(viewModel: MediaLibraryViewModel) {
    val target by viewModel.castTarget.collectAsState()
    var picking by remember { mutableStateOf(false) }
    IconButton(onClick = { picking = true }) {
        Icon(
            if (target != null) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = "Cast",
            tint = if (target != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (picking) CastPicker(viewModel, current = target, onDismiss = { picking = false })
}

@Composable
private fun CastPicker(viewModel: MediaLibraryViewModel, current: CastTarget?, onDismiss: () -> Unit) {
    var targets by remember { mutableStateOf<List<CastTarget>?>(null) }
    var search by remember { mutableIntStateOf(0) }
    LaunchedEffect(search) {
        targets = null
        targets = runCatching { viewModel.discoverCastTargets() }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cast to a TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                current?.let {
                    Text("Connected to ${it.name}. Tap a video, song or photo to play it there.", style = MaterialTheme.typography.bodySmall)
                }
                val list = targets
                when {
                    list == null -> Text("Looking for TVs on your Wi-Fi…")
                    list.isEmpty() -> Text(
                        "No TVs found. Check the TV is on and on the same Wi-Fi as this phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list) { t ->
                            ListItem(
                                headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(if (t.kind == "chromecast") "Google Cast" else "DLNA (e.g. Samsung)") },
                                leadingContent = { Icon(Icons.Filled.Tv, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    viewModel.selectCastTarget(t)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (current != null) {
                TextButton(onClick = {
                    viewModel.selectCastTarget(null)
                    onDismiss()
                }) { Text("Disconnect") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { search++ }, enabled = targets != null) { Text("Search again") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

private fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Remote control for whatever is playing on the TV. */
@Composable
fun CastBar(viewModel: MediaLibraryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.castState.collectAsState()
    val s = state ?: return
    var dragging by remember { mutableStateOf<Float?>(null) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.itemName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when (s.state) {
                            "error" -> s.error ?: "Couldn't cast."
                            "loading" -> "Starting on ${s.deviceName}…"
                            "paused" -> "Paused on ${s.deviceName}"
                            "stopped" -> "Finished on ${s.deviceName}"
                            else -> "Playing on ${s.deviceName}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (s.state != "error" && s.state != "stopped") {
                    IconButton(onClick = { viewModel.castControl(if (s.state == "paused") "play" else "pause") }, enabled = s.state != "loading") {
                        Icon(if (s.state == "paused") Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = if (s.state == "paused") "Play" else "Pause")
                    }
                }
                IconButton(onClick = { viewModel.stopCasting() }) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
            }
            if (s.duration > 0 && s.state != "error" && s.state != "stopped") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(clock(dragging?.toDouble() ?: s.position), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = (dragging ?: s.position.toFloat()).coerceIn(0f, s.duration.toFloat()),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { viewModel.castControl("seek", it.toDouble()) }
                            dragging = null
                        },
                        valueRange = 0f..s.duration.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(clock(s.duration), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
