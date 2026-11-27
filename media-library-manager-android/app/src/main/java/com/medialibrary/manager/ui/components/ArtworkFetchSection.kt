package com.medialibrary.manager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.AppSettings
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.ui.MediaLibraryViewModel

/** Items in the library folder [path] — only those directly in it, or also everything below. */
private fun itemsInFolder(items: List<MediaItem>, settings: AppSettings, path: List<String>, includeSubfolders: Boolean): List<MediaItem> =
    items.filter { item ->
        val segments = segmentsForItem(item, settings)
        segments.size >= path.size &&
            path.indices.all { segments[it] == path[it] } &&
            (includeSubfolders || segments.size == path.size)
    }

/**
 * Settings → "Fetch artwork & info": browse to a folder of the library (the whole library by
 * default), optionally with its subfolders and Force, and fetch. Progress shows in the same
 * per-item dialog as before.
 */
@Composable
fun ArtworkFetchSection(viewModel: MediaLibraryViewModel) {
    val items by viewModel.libraryItems.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val fetching by viewModel.isFetchingArtwork.collectAsState()
    val scanEvents by viewModel.scanEvents.collectAsState()
    val showScanDialog by viewModel.showScanDialog.collectAsState()
    var path by remember { mutableStateOf(listOf<String>()) }
    var picking by remember { mutableStateOf(false) }
    var includeSubfolders by remember { mutableStateOf(true) }
    var force by remember { mutableStateOf(false) }

    val scope = itemsInFolder(items, settings, path, includeSubfolders)

    if (picking) {
        LibraryFolderDialog(
            items = items,
            settings = settings,
            initialPath = path,
            onPick = { path = it; picking = false },
            onDismiss = { picking = false }
        )
    }
    if (showScanDialog) {
        ScanProgressDialog(
            running = fetching,
            events = scanEvents,
            onCancel = { viewModel.cancelFetchInfo() },
            onClose = { viewModel.dismissScanDialog() }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fetch artwork & info", style = MaterialTheme.typography.titleMedium)
        Text(
            "Looks up posters, titles and years for the files in a folder of your library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "📁 " + if (path.isEmpty()) "Whole library" else path.joinToString(" / "),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedButton(onClick = { picking = true }) { Text("Browse…") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeSubfolders, onCheckedChange = { includeSubfolders = it })
            Text("Include subfolders")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = force, onCheckedChange = { force = it })
            Text("Force (re-fetch, replacing what was found before)")
        }
        Button(
            onClick = { viewModel.fetchInfo(scope.map { it.id }.toSet(), force) },
            enabled = !fetching && scope.isNotEmpty()
        ) {
            Text(
                if (fetching) "Fetching artwork & info…"
                else "Fetch artwork & info (${scope.size} file${if (scope.size == 1) "" else "s"})"
            )
        }
    }
}

/** Browse the library's folder tree (Local + each share) and pick the folder that's open. */
@Composable
private fun LibraryFolderDialog(
    items: List<MediaItem>,
    settings: AppSettings,
    initialPath: List<String>,
    onPick: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var path by remember { mutableStateOf(initialPath) }
    val segmentsList = remember(items, settings) { items.map { segmentsForItem(it, settings) } }
    val subfolders = sortedSetOf<String>()
    var filesBelow = 0
    for (segments in segmentsList) {
        if (segments.size < path.size || path.indices.any { segments[it] != path[it] }) continue
        filesBelow++
        if (segments.size > path.size) subfolders += segments[path.size]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path.isNotEmpty()) {
                        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, "Up", { path = path.dropLast(1) })
                    }
                    Text(
                        if (path.isEmpty()) "Whole library" else path.joinToString(" / "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "$filesBelow file${if (filesBelow == 1) "" else "s"} here and below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (subfolders.isEmpty()) {
                    Text("No subfolders here.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(subfolders.toList(), key = { it }) { name ->
                        ListItem(
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { path = path + name }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
