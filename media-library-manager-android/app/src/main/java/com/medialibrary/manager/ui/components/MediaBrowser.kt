package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.AppSettings
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaSourceType

/** The folder path segments an item sits under, prefixed with a top-level grouping segment — "Local"
 *  (plus that item's own configured folder's label, when more than one local folder is configured and
 *  their relative paths could otherwise collide) for a local item, or that item's network share's
 *  label for a network item — so Library and Network are one browsable tree whose first level is
 *  "Local" plus one folder per connected share, instead of two separate screens. */
private fun segmentsForItem(item: MediaItem, settings: AppSettings): List<String> {
    val top = if (item.sourceType == MediaSourceType.LOCAL) {
        if (settings.localFolders.size > 1 && item.folderId != null) {
            val label = settings.localFolders.find { it.id == item.folderId }?.label
            if (label != null) listOf("Local", label) else listOf("Local")
        } else {
            listOf("Local")
        }
    } else {
        listOf(settings.networkShares.find { it.id == item.shareId }?.label ?: "Network")
    }
    return top + item.folderPath.split("/").filter { it.isNotEmpty() }
}

/**
 * Groups a flat item list into a navigable folder tree using [segmentsForItem] when no filter is
 * active. Any active filter (name text, tags, or a date range) switches to a flat search across
 * every folder instead — browsing one folder and filtering the whole library are different
 * intents. All active filter kinds combine with AND; selecting several tags matches an item with
 * ANY of them. "Fetch artwork & info" is scoped to whatever folder is currently being browsed
 * (optionally including its subfolders), not the whole library — see [onFetchInfo].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaBrowser(
    items: List<MediaItem>,
    settings: AppSettings,
    filter: String,
    tagFilter: List<String> = emptyList(),
    dateFrom: Long? = null,
    dateTo: Long? = null,
    onOpen: (MediaItem) -> Unit,
    onItemUpdated: (title: String, year: String, tags: List<String>, artworkUrl: String?, item: MediaItem) -> Unit,
    onClearInfo: (MediaItem) -> Unit,
    onFetchInfo: (itemIds: Set<String>, force: Boolean) -> Unit,
    fetchingInfo: Boolean,
    onPickImage: ((onPicked: (String) -> Unit) -> Unit)? = null,
    onDownload: ((MediaItem) -> Unit)? = null,
    downloadingItemId: String? = null,
    modifier: Modifier = Modifier
) {
    var path by remember { mutableStateOf(listOf<String>()) }
    var includeSubfolders by remember { mutableStateOf(false) }
    var forceRefetch by remember { mutableStateOf(false) }

    val allTags = remember(items) { items.flatMap { it.tags }.distinct().sorted() }

    val hasActiveFilter = filter.isNotBlank() || tagFilter.isNotEmpty() || dateFrom != null || dateTo != null
    fun matches(item: MediaItem): Boolean {
        if (filter.isNotBlank() && !item.name.contains(filter, ignoreCase = true)) return false
        if (tagFilter.isNotEmpty() && item.tags.none { it in tagFilter }) return false
        if (dateFrom != null && item.modifiedAtMillis < dateFrom) return false
        if (dateTo != null && item.modifiedAtMillis > dateTo) return false
        return true
    }

    @Composable
    fun FetchInfoRow(scope: List<MediaItem>, showSubfolderToggle: Boolean) {
        // FlowRow, not a plain Row: on a narrow phone (e.g. a folded Galaxy Z Flip's cover/inner
        // width), two checkboxes + labels + this button don't all fit on one line. A plain Row
        // would squeeze the button down to almost no width instead of letting it keep its natural
        // size, wrapping its text across many lines into a tall unreadable sliver; a scrollable
        // Row keeps the size right but hides the button off-screen with no visible hint to swipe.
        // Wrapping onto a second line instead guarantees every control is actually visible,
        // full-size, with no gesture required.
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showSubfolderToggle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSubfolders, onCheckedChange = { includeSubfolders = it })
                    Text("Include subfolders")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = forceRefetch, onCheckedChange = { forceRefetch = it })
                Text("Force")
            }
            Button(
                onClick = { onFetchInfo(scope.map { it.id }.toSet(), forceRefetch) },
                enabled = !fetchingInfo && scope.isNotEmpty()
            ) {
                Text(if (fetchingInfo) "Fetching artwork & info…" else "Fetch artwork & info", maxLines = 1)
            }
        }
    }

    if (hasActiveFilter) {
        val matched = items.filter(::matches)
        Column(modifier = modifier) {
            FetchInfoRow(matched, showSubfolderToggle = false)
            MediaGrid(
                items = matched,
                allTags = allTags,
                onOpen = onOpen,
                onItemUpdated = onItemUpdated,
                onClearInfo = onClearInfo,
                onPickImage = onPickImage,
                onDownload = onDownload,
                downloadingItemId = downloadingItemId,
                modifier = Modifier.weight(1f)
            )
        }
        return
    }

    val depth = path.size
    val subfolders = sortedSetOf<String>()
    val filesHere = mutableListOf<MediaItem>()
    val filesHereAndBelow = mutableListOf<MediaItem>()
    for (item in items) {
        val segments = segmentsForItem(item, settings)
        if (segments.size < depth) continue
        if ((0 until depth).any { segments.getOrNull(it) != path[it] }) continue
        filesHereAndBelow += item
        if (segments.size == depth) {
            filesHere += item
        } else {
            subfolders += segments[depth]
        }
    }
    val scope = if (includeSubfolders) filesHereAndBelow else filesHere

    Column(modifier = modifier) {
        if (depth > 0 || subfolders.isNotEmpty()) {
            LazyRow {
                item {
                    TextButton(onClick = { path = emptyList() }, enabled = depth > 0) { Text("Root") }
                }
                itemsIndexed(path) { index, segment ->
                    Text(" / ", modifier = Modifier.padding(top = 10.dp))
                    TextButton(onClick = { path = path.take(index + 1) }, enabled = index != depth - 1) {
                        Text(segment)
                    }
                }
            }
        }
        FetchInfoRow(scope, showSubfolderToggle = subfolders.isNotEmpty())
        if (subfolders.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(subfolders.toList()) { name ->
                    AssistChip(onClick = { path = path + name }, label = { Text("📁 $name") })
                }
            }
        }
        if (filesHere.isNotEmpty() || subfolders.isEmpty()) {
            MediaGrid(
                items = filesHere,
                allTags = allTags,
                onOpen = onOpen,
                onItemUpdated = onItemUpdated,
                onClearInfo = onClearInfo,
                onPickImage = onPickImage,
                onDownload = onDownload,
                downloadingItemId = downloadingItemId,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
