package com.medialibrary.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.remote.RemoteDevice
import com.medialibrary.manager.ui.MediaLibraryViewModel
import kotlinx.coroutines.launch

private fun seen(lastSeen: Long): String {
    val minutes = (System.currentTimeMillis() - lastSeen) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** Pick another device (same mailbox share) to start a download on. */
@Composable
fun SendToDialog(
    viewModel: MediaLibraryViewModel,
    source: String,
    title: String?,
    onDismiss: () -> Unit,
    onSent: (String) -> Unit
) {
    var devices by remember { mutableStateOf<List<RemoteDevice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { viewModel.remoteDevices() }
            .onSuccess { devices = it }
            .onFailure { error = it.message ?: "Couldn't reach the shared folder." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download on another device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                val list = devices
                when {
                    list == null && error == null -> Text("Looking for devices…")
                    list != null && list.isEmpty() -> Text(
                        "No other devices yet. Open the app on the other device and pick the same network share under " +
                            "Settings → Send to another device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    list != null -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list) { device ->
                            ListItem(
                                headlineContent = { Text(device.name) },
                                supportingContent = {
                                    Text(
                                        "${device.platform} · " + (if (device.isOnline()) "online" else "seen ${seen(device.lastSeen)}") +
                                            if (sending == device.id) " · sending…" else ""
                                    )
                                },
                                leadingContent = {
                                    Box(
                                        Modifier.size(10.dp).background(
                                            if (device.isOnline()) Color(0xFF6FCE8A) else MaterialTheme.colorScheme.outline,
                                            CircleShape
                                        )
                                    )
                                },
                                modifier = Modifier.clickable(enabled = sending == null) {
                                    sending = device.id
                                    error = null
                                    scope.launch {
                                        runCatching { viewModel.sendTo(device, source, title) }
                                            .onSuccess(onSent)
                                            .onFailure {
                                                error = it.message ?: "Couldn't send it."
                                                sending = null
                                            }
                                    }
                                }
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
