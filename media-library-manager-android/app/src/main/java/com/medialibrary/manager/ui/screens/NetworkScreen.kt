package com.medialibrary.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.FilterBar
import com.medialibrary.manager.ui.components.MediaBrowser
import java.time.LocalDate
import java.time.ZoneId

private fun parseDateMillis(text: String): Long? =
    runCatching { LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

@Composable
fun NetworkScreen(viewModel: MediaLibraryViewModel) {
    val settings by viewModel.settings.collectAsState()
    val items by viewModel.networkItems.collectAsState()
    val scanning by viewModel.isScanningNetwork.collectAsState()
    val fetchingArtwork by viewModel.isFetchingArtwork.collectAsState()
    val downloadingItemId by viewModel.downloadingItemId.collectAsState()
    var filter by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(listOf<String>()) }
    var dateFrom by remember { mutableStateOf("") }
    var dateTo by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { viewModel.scanNetwork() }, enabled = !scanning) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(4.dp))
                Text(if (scanning) "Scanning…" else "Scan shares")
            }
        }
        Row {
            Button(onClick = { viewModel.fetchInfo() }, enabled = !fetchingArtwork) {
                Text(if (fetchingArtwork) "Fetching artwork & info…" else "Fetch artwork & info")
            }
        }
        if (settings.networkShares.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("No SMB shares configured yet. Add one in Settings.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap an item to stream it live (seekable, nothing saved to disk); use the download " +
                "icon on a card to save a full local copy to Downloads/MediaLibraryManager instead. " +
                "\"Fetch artwork & info\" writes a .metadata.json sidecar onto the share itself, so " +
                "any device browsing it sees the same info without re-fetching.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        FilterBar(
            items = items,
            selectedTags = selectedTags,
            onToggleTag = { tag -> selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag },
            dateFrom = dateFrom,
            dateTo = dateTo,
            onDateFromChange = { dateFrom = it },
            onDateToChange = { dateTo = it }
        )
        Spacer(Modifier.height(8.dp))
        MediaBrowser(
            items = items,
            filter = filter,
            tagFilter = selectedTags,
            dateFrom = parseDateMillis(dateFrom),
            dateTo = parseDateMillis(dateTo)?.plus(86_399_999L),
            onOpen = { viewModel.playNetworkItem(it) },
            onItemUpdated = { title, year, tags, item -> viewModel.updateItemMetadata(title, year, tags, item) },
            onDownload = { viewModel.downloadNetworkItem(it) },
            downloadingItemId = downloadingItemId,
            modifier = Modifier.weight(1f)
        )
    }
}
