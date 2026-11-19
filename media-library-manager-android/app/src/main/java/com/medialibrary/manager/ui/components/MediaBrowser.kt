package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.MediaItem

/**
 * Groups a flat item list into a navigable folder tree using each item's [MediaItem.folderPath]
 * (captured at scan time by each scanner) when no filter is active. Any active filter (name text,
 * tags, or a date range) switches to a flat search across every folder instead — browsing one
 * folder and filtering the whole library are different intents. All active filter kinds combine
 * with AND; selecting several tags matches an item with ANY of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBrowser(
    items: List<MediaItem>,
    filter: String,
    tagFilter: List<String> = emptyList(),
    dateFrom: Long? = null,
    dateTo: Long? = null,
    onOpen: (MediaItem) -> Unit,
    onItemUpdated: (title: String, year: String, tags: List<String>, item: MediaItem) -> Unit,
    onDownload: ((MediaItem) -> Unit)? = null,
    downloadingItemId: String? = null,
    modifier: Modifier = Modifier
) {
    var path by remember { mutableStateOf(listOf<String>()) }

    val allTags = remember(items) { items.flatMap { it.tags }.distinct().sorted() }

    val hasActiveFilter = filter.isNotBlank() || tagFilter.isNotEmpty() || dateFrom != null || dateTo != null
    fun matches(item: MediaItem): Boolean {
        if (filter.isNotBlank() && !item.name.contains(filter, ignoreCase = true)) return false
        if (tagFilter.isNotEmpty() && item.tags.none { it in tagFilter }) return false
        if (dateFrom != null && item.modifiedAtMillis < dateFrom) return false
        if (dateTo != null && item.modifiedAtMillis > dateTo) return false
        return true
    }

    if (hasActiveFilter) {
        val matched = items.filter(::matches)
        MediaGrid(
            items = matched,
            allTags = allTags,
            onOpen = onOpen,
            onItemUpdated = onItemUpdated,
            onDownload = onDownload,
            downloadingItemId = downloadingItemId,
            modifier = modifier
        )
        return
    }

    val depth = path.size
    val subfolders = sortedSetOf<String>()
    val filesHere = mutableListOf<MediaItem>()
    for (item in items) {
        val segments = item.folderPath.split("/").filter { it.isNotEmpty() }
        if (segments.size < depth) continue
        if ((0 until depth).any { segments.getOrNull(it) != path[it] }) continue
        if (segments.size == depth) {
            filesHere += item
        } else {
            subfolders += segments[depth]
        }
    }

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
                onDownload = onDownload,
                downloadingItemId = downloadingItemId,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
