package com.medialibrary.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.MediaGrid

@Composable
fun LibraryScreen(viewModel: MediaLibraryViewModel) {
    val context = LocalContext.current
    val items by viewModel.localItems.collectAsState()
    val scanning by viewModel.isScanningLocal.collectAsState()
    val fetchingArtwork by viewModel.isFetchingArtwork.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var filter by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { viewModel.scanLocal() }, enabled = !scanning) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(4.dp))
                Text(if (scanning) "Scanning…" else "Scan")
            }
        }
        Row {
            Button(onClick = { viewModel.fetchArtwork() }, enabled = !fetchingArtwork) {
                Text(if (fetchingArtwork) "Fetching artwork…" else "Fetch artwork")
            }
        }
        Text(
            "Looks up covers/posters via the iTunes catalog based on each file's name — best-effort, covers the whole library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (settings.localFolders.isEmpty()) {
                "Scans the whole device's media via MediaStore. Add folders in Settings to scope this."
            } else {
                "Scanning ${settings.localFolders.size} configured folder(s)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        val filtered = remember(items, filter) { items.filter { it.name.contains(filter, ignoreCase = true) } }
        MediaGrid(
            items = filtered,
            onOpen = { item: MediaItem -> viewModel.openLocalItem(context, item) },
            modifier = Modifier.weight(1f)
        )
    }
}
