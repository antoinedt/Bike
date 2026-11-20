package com.medialibrary.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.FilterBar
import com.medialibrary.manager.ui.components.MediaBrowser
import com.medialibrary.manager.ui.components.ScanProgressDialog
import java.time.LocalDate
import java.time.ZoneId

private fun parseDateMillis(text: String): Long? =
    runCatching { LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

/**
 * Local folders and network shares live in one browsable library here (see MediaBrowser's "Local"
 * + per-share top-level grouping) rather than two separate screens/tabs. Tapping a local item
 * opens it with the OS's default app; tapping a network item streams it live in-app; only network
 * items offer a Download action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(viewModel: MediaLibraryViewModel, onPickImage: (onPicked: (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    val items by viewModel.libraryItems.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val scanningLocal by viewModel.isScanningLocal.collectAsState()
    val scanningNetwork by viewModel.isScanningNetwork.collectAsState()
    val fetchingArtwork by viewModel.isFetchingArtwork.collectAsState()
    val scanEvents by viewModel.scanEvents.collectAsState()
    val showScanDialog by viewModel.showScanDialog.collectAsState()
    val downloadingItemId by viewModel.downloadingItemId.collectAsState()
    var filter by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(listOf<String>()) }
    var dateFrom by remember { mutableStateOf("") }
    var dateTo by remember { mutableStateOf("") }

    if (showScanDialog) {
        ScanProgressDialog(
            running = fetchingArtwork,
            events = scanEvents,
            onCancel = { viewModel.cancelFetchInfo() },
            onClose = { viewModel.dismissScanDialog() }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Filter gets the full width on its own line, and the two Scan buttons wrap onto a
        // second line (FlowRow) if they don't both fit next to it — on a narrow screen (e.g. a
        // folded Galaxy Z Flip), cramming the filter field and both buttons into one plain Row
        // squeezed the filter field down to an unusable, unreadable sliver instead.
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.scanLocal() }, enabled = !scanningLocal) {
                if (scanningLocal) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(4.dp))
                Text(if (scanningLocal) "Scanning…" else "Scan folders")
            }
            Button(onClick = { viewModel.scanNetwork() }, enabled = !scanningNetwork) {
                if (scanningNetwork) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(4.dp))
                Text(if (scanningNetwork) "Scanning…" else "Scan shares")
            }
        }
        Text(
            "Local folders and network shares live in one library here — browse into \"Local\" or " +
                "any connected share's folder below. \"Fetch artwork & info\" (further down) only " +
                "covers the folder you're currently browsing, with an option to include its " +
                "subfolders. Network playback streams live from the share (seekable, nothing saved " +
                "to disk); use a card's download icon to save a full local copy instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settings.localFolders.isEmpty() && settings.networkShares.isEmpty()) {
            Text(
                "Nothing configured yet. Add a local folder or an SMB share in Settings to start scanning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
            settings = settings,
            filter = filter,
            tagFilter = selectedTags,
            dateFrom = parseDateMillis(dateFrom),
            dateTo = parseDateMillis(dateTo)?.plus(86_399_999L),
            onOpen = { item: MediaItem ->
                if (item.sourceType == MediaSourceType.LOCAL) {
                    viewModel.openLocalItem(context, item)
                } else {
                    viewModel.playNetworkItem(item)
                }
            },
            onItemUpdated = { title, year, tags, artworkUrl, item ->
                viewModel.updateItemMetadata(title, year, tags, artworkUrl, item)
            },
            onClearInfo = { item -> viewModel.clearItemMetadata(item) },
            onFetchInfo = { itemIds, force -> viewModel.fetchInfo(itemIds, force) },
            fetchingInfo = fetchingArtwork,
            onPickImage = onPickImage,
            onDownload = { viewModel.downloadNetworkItem(it) },
            downloadingItemId = downloadingItemId,
            modifier = Modifier.weight(1f)
        )
    }
}
