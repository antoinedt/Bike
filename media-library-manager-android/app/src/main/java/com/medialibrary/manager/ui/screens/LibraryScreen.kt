package com.medialibrary.manager.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.ImeAction
import com.medialibrary.manager.model.SearchResultItem
import com.medialibrary.manager.model.SiteProfile
import com.medialibrary.manager.ui.components.SearchResultRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.FolderPickerDialog
import com.medialibrary.manager.ui.components.MediaBrowser
import com.medialibrary.manager.ui.components.TagFilterChips

/**
 * Local folders and network shares live in one browsable library here (see MediaBrowser's "Local"
 * + per-share top-level grouping). Tapping a local item opens it with the OS's default app; tapping
 * a network item streams it live in-app. Every item has "Copy to…" (any folder on this device or a
 * network share), which runs in the background; progress shows on the tile and in Downloads.
 */
@Composable
fun LibraryScreen(viewModel: MediaLibraryViewModel, isTv: Boolean, onPickImage: (onPicked: (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    val items by viewModel.libraryItems.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val copyingItemId by viewModel.copyingItemId.collectAsState()
    val copyProgress by viewModel.copyProgress.collectAsState()
    var copyingFrom by remember { mutableStateOf<MediaItem?>(null) }
    val castTarget by viewModel.castTarget.collectAsState()
    val scanningLocal by viewModel.isScanningLocal.collectAsState()
    val scanningNetwork by viewModel.isScanningNetwork.collectAsState()
    val scanFound by viewModel.scanFound.collectAsState()
    var filter by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(listOf<String>()) }
    // Search sites: the term last sent with the Search button (null = no site search shown), and
    // which sites to ask (empty = all of them).
    var webQuery by remember { mutableStateOf<String?>(null) }
    var webSites by remember { mutableStateOf(setOf<String>()) }
    var sendingResult by remember { mutableStateOf<SearchResultItem?>(null) }
    val webResults by viewModel.searchResults.collectAsState()
    val searching by viewModel.isSearching.collectAsState()
    val downloadingUrl by viewModel.downloadingUrl.collectAsState()

    // Typing filters the library as you go; the Search button (or the keyboard's search key) also
    // asks the search sites, and their results are listed below the library's.
    fun searchSites() {
        val term = filter.trim()
        if (term.isEmpty()) return
        if (settings.siteProfiles.isEmpty()) {
            viewModel.showMessage("Add a search site in Settings to search for things to download.")
            return
        }
        webQuery = term
        viewModel.search(term, webSites)
    }

    fun onSearchTextChange(text: String) {
        filter = text
        if (text.isBlank() && webQuery != null) {
            webQuery = null
            viewModel.clearSearch()
        }
    }

    sendingResult?.let { result ->
        com.medialibrary.manager.ui.components.SendToDialog(
            viewModel = viewModel,
            source = result.downloadUrl,
            title = result.title,
            onDismiss = { sendingResult = null },
            onSent = { message ->
                sendingResult = null
                viewModel.showMessage(message)
            }
        )
    }

    val query = webQuery
    val siteResults: (LazyGridScope.() -> Unit)? = if (isTv || query == null) null else fun LazyGridScope.() {
        item(key = "web-header", span = { GridItemSpan(maxLineSpan) }) {
            SiteSearchHeader(
                query = query,
                searching = searching,
                count = webResults.size,
                sites = settings.siteProfiles,
                selected = webSites,
                onToggleSite = { id ->
                    webSites = if (id in webSites) webSites - id else webSites + id
                    viewModel.search(query, webSites)
                }
            )
        }
        itemsIndexed(webResults, key = { i, r -> "web:$i:${r.downloadUrl}" }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { _, result ->
            SearchResultRow(
                result = result,
                downloadingUrl = downloadingUrl,
                onDownload = { viewModel.download(it) },
                onOpenInOtherApp = { viewModel.openInOtherApp(it) },
                onSendTo = if (settings.remoteShareId.isNotBlank()) ({ sendingResult = it }) else null
            )
        }
    }

    val filterRequester = remember { FocusRequester() }

    copyingFrom?.let { item ->
        FolderPickerDialog(
            title = "Copy \"${item.title ?: item.name}\" to…",
            confirmLabel = "Copy here",
            settings = settings,
            viewModel = viewModel,
            onPick = { target ->
                viewModel.copyItem(item, target)
                copyingFrom = null
            },
            onDismiss = { copyingFrom = null }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (isTv) 8.dp else 12.dp)) {
        // The library re-reads its folders and shares in the background (at launch, and after a
        // scan/copy/download) and fills in as it goes.
        if (scanningLocal || scanningNetwork) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (scanFound > 0) "Scanning… $scanFound found" else "Scanning…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (settings.localFolders.isEmpty() && settings.networkShares.isEmpty()) {
            Text(
                "Nothing configured yet. Add a local folder or an SMB share in Settings — it's scanned automatically.",
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
                    // A TV is chosen with the Cast button: play it there instead.
                    castTarget != null && item.kind != MediaKind.OTHER -> viewModel.castItem(item)
                    item.sourceType == MediaSourceType.NETWORK -> viewModel.playNetworkItem(item)
                    item.kind == MediaKind.VIDEO || item.kind == MediaKind.AUDIO -> viewModel.playLocalItem(context, item)
                    else -> viewModel.openLocalItem(context, item)
                }
            },
            onItemUpdated = { title, year, tags, artworkUrl, item ->
                viewModel.updateItemMetadata(title, year, tags, artworkUrl, item)
            },
            onClearInfo = { item -> viewModel.clearItemMetadata(item) },
            onPickImage = onPickImage,
            onCopy = { copyingFrom = it },
            copyingItemId = copyingItemId,
            copyProgress = copyProgress,
            isTv = isTv,
            filterField = {
                // A placeholder rather than a floating label keeps the field one line tall.
                if (isTv) {
                    AppTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.width(280.dp).focusRequester(filterRequester)
                    )
                } else {
                    // Phone: Search (filters the library as you type) + search-sites button + Cast.
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AppTextField(
                            value = filter,
                            onValueChange = ::onSearchTextChange,
                            placeholder = { Text("Search") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { searchSites() }),
                            modifier = Modifier.weight(1f).focusRequester(filterRequester)
                        )
                        IconButton(onClick = { searchSites() }, enabled = filter.isNotBlank() && !searching) {
                            Icon(Icons.Filled.Search, contentDescription = "Search sites")
                        }
                        com.medialibrary.manager.ui.components.CastButton(viewModel)
                    }
                }
            },
            tagChips = {
                TagFilterChips(
                    items = items,
                    selectedTags = selectedTags,
                    onToggleTag = { tag -> selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag }
                )
            },
            topBarFirst = filterRequester,
            gridFooter = siteResults,
            modifier = Modifier.weight(1f)
        )
        if (!isTv) com.medialibrary.manager.ui.components.CastBar(viewModel, Modifier.padding(top = 6.dp))
    }
}

/** Heading above the search-site results: what was searched, how it went, and which sites to ask. */
@Composable
private fun SiteSearchHeader(
    query: String,
    searching: Boolean,
    count: Int,
    sites: List<SiteProfile>,
    selected: Set<String>,
    onToggleSite: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text("From your search sites", style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                searching -> "Searching for \"$query\"…"
                count == 0 -> "No results for \"$query\"."
                else -> "$count result${if (count == 1) "" else "s"} for \"$query\""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (sites.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sites.forEach { site ->
                    FilterChip(selected = site.id in selected, onClick = { onToggleSite(site.id) }, label = { Text(site.label) })
                }
            }
            Text(
                if (selected.isEmpty()) "Asking every site — tap to pick some." else "Asking ${selected.size} of ${sites.size} sites.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
