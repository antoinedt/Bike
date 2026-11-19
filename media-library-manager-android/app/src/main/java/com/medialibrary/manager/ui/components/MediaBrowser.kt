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
 * (captured at scan time by each scanner) when [filter] is empty. A non-empty filter switches to
 * a flat, recursive name search across every folder instead — browsing one folder and searching
 * everything are different intents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBrowser(
    items: List<MediaItem>,
    filter: String,
    onOpen: (MediaItem) -> Unit,
    onDownload: ((MediaItem) -> Unit)? = null,
    downloadingItemId: String? = null,
    modifier: Modifier = Modifier
) {
    var path by remember { mutableStateOf(listOf<String>()) }

    if (filter.isNotBlank()) {
        val matched = items.filter { it.name.contains(filter, ignoreCase = true) }
        MediaGrid(
            items = matched,
            onOpen = onOpen,
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
                onOpen = onOpen,
                onDownload = onDownload,
                downloadingItemId = downloadingItemId,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
