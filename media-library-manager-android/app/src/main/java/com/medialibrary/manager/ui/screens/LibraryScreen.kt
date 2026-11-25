package com.medialibrary.manager.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.medialibrary.manager.ui.components.AppTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.MediaBrowser
import com.medialibrary.manager.ui.components.ScanProgressDialog
import com.medialibrary.manager.ui.components.TagFilterChips

/**
 * Local folders and network shares live in one browsable library here (see MediaBrowser's "Local"
 * + per-share top-level grouping). Tapping a local item opens it with the OS's default app; tapping
 * a network item streams it live in-app; only network items offer a Download action.
 */
@Composable
fun LibraryScreen(viewModel: MediaLibraryViewModel, isTv: Boolean, onPickImage: (onPicked: (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    val items by viewModel.libraryItems.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val scanningLocal by viewModel.isScanningLocal.collectAsState()
    val scanningNetwork by viewModel.isScanningNetwork.collectAsState()
    val fetchingArtwork by viewModel.isFetchingArtwork.collectAsState()
    val scanEvents by viewModel.scanEvents.collectAsState()
    val showScanDialog by viewModel.showScanDialog.collectAsState()
    val downloadingItemId by viewModel.downloadingItemId.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    var filter by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(listOf<String>()) }

    val filterRequester = remember { FocusRequester() }
    val scanFoldersRequester = remember { FocusRequester() }
    val scanSharesRequester = remember { FocusRequester() }
    // A disabled (scanning) button can't take focus, so point at the first one that can.
    val firstEnabledScanButton = when {
        !scanningLocal -> scanFoldersRequester
        !scanningNetwork -> scanSharesRequester
        else -> null
    }

    if (showScanDialog) {
        ScanProgressDialog(
            running = fetchingArtwork,
            events = scanEvents,
            onCancel = { viewModel.cancelFetchInfo() },
            onClose = { viewModel.dismissScanDialog() }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (isTv) 8.dp else 12.dp)) {
        if (settings.localFolders.isEmpty() && settings.networkShares.isEmpty()) {
            Text(
                "Nothing configured yet. Add a local folder or an SMB share in Settings to start scanning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MediaBrowser(
            items = items,
            settings = settings,
            filter = filter,
            tagFilter = selectedTags,
            onOpen = { item: MediaItem ->
                when {
                    item.sourceType == MediaSourceType.NETWORK -> viewModel.playNetworkItem(item)
                    item.kind == MediaKind.VIDEO || item.kind == MediaKind.AUDIO -> viewModel.playLocalItem(context, item)
                    else -> viewModel.openLocalItem(context, item)
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
            downloadProgress = downloadProgress,
            isTv = isTv,
            filterField = {
                // A placeholder rather than a floating label keeps the field one line tall.
                AppTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter") },
                    singleLine = true,
                    modifier = (if (isTv) Modifier.width(280.dp) else Modifier.fillMaxWidth())
                        .focusRequester(filterRequester)
                )
            },
            tagChips = {
                TagFilterChips(
                    items = items,
                    selectedTags = selectedTags,
                    onToggleTag = { tag -> selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag }
                )
            },
            scanButtons = {
                ScanButton("Scan folders", scanningLocal, scanFoldersRequester) { viewModel.scanLocal() }
                ScanButton("Scan shares", scanningNetwork, scanSharesRequester) { viewModel.scanNetwork() }
            },
            topBarFirst = filterRequester,
            bottomBarFirst = firstEnabledScanButton,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ScanButton(label: String, scanning: Boolean, focusRequester: FocusRequester, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !scanning, modifier = Modifier.focusRequester(focusRequester)) {
        if (scanning) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null)
        }
        Spacer(Modifier.width(4.dp))
        Text(if (scanning) "Scanning…" else label)
    }
}
