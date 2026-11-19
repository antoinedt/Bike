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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.FilterBar
import com.medialibrary.manager.ui.components.MediaBrowser
import java.time.LocalDate
import java.time.ZoneId

private fun parseDateMillis(text: String): Long? =
    runCatching { LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

@Composable
fun LibraryScreen(viewModel: MediaLibraryViewModel) {
    val context = LocalContext.current
    val items by viewModel.localItems.collectAsState()
    val scanning by viewModel.isScanningLocal.collectAsState()
    val fetchingArtwork by viewModel.isFetchingArtwork.collectAsState()
    val settings by viewModel.settings.collectAsState()
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
            Button(onClick = { viewModel.fetchInfo() }, enabled = !fetchingArtwork) {
                Text(if (fetchingArtwork) "Fetching artwork & info…" else "Fetch artwork & info")
            }
        }
        Text(
            "Looks up title/year/cover art via iTunes, falling back to a frame pulled from the " +
                "video itself when nothing matches (most useful for home videos, which iTunes " +
                "won't know about) — covers the whole library, local and network. Saved on this " +
                "device so it only needs to happen once.",
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
            onOpen = { item: MediaItem -> viewModel.openLocalItem(context, item) },
            onItemUpdated = { title, year, tags, item -> viewModel.updateItemMetadata(title, year, tags, item) },
            modifier = Modifier.weight(1f)
        )
    }
}
