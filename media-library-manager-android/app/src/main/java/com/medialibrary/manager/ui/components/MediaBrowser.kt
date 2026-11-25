package com.medialibrary.manager.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
 * The Library's browsing area: a slim top bar (filter field, folder path, tag chips), the grid of
 * subfolders + files taking all the remaining height, and a slim bottom bar (scan buttons, then
 * "Fetch artwork & info" and its options). With no filter active it browses one folder at a time
 * using [segmentsForItem]; any active filter (name text or tags) switches to a flat search across
 * every folder instead — browsing one folder and filtering the whole library are different
 * intents. Several selected tags match an item with ANY of them. "Fetch artwork & info" is scoped
 * to the folder being browsed (optionally with its subfolders), or to the filter's matches.
 *
 * On a TV ([isTv]) the top bar is a single line, and the remote moves between bars and grid
 * by landing on the first control/tile, not on whatever happens to be nearest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaBrowser(
    items: List<MediaItem>,
    settings: AppSettings,
    filter: String,
    tagFilter: List<String> = emptyList(),
    onOpen: (MediaItem) -> Unit,
    onItemUpdated: (title: String, year: String, tags: List<String>, artworkUrl: String?, item: MediaItem) -> Unit,
    onClearInfo: (MediaItem) -> Unit,
    onFetchInfo: (itemIds: Set<String>, force: Boolean) -> Unit,
    fetchingInfo: Boolean,
    onPickImage: ((onPicked: (String) -> Unit) -> Unit)? = null,
    onCopy: ((MediaItem) -> Unit)? = null,
    copyingItemId: String? = null,
    copyProgress: Float? = null,
    isTv: Boolean = false,
    /** First thing in the top bar. */
    filterField: @Composable () -> Unit = {},
    /** Last thing in the top bar. */
    tagChips: @Composable () -> Unit = {},
    /** First things in the bottom bar. */
    scanButtons: @Composable () -> Unit = {},
    /** Attached (by the caller) to [filterField]: where Up from the grid's top row goes. */
    topBarFirst: FocusRequester? = null,
    /** Attached (by the caller) to the first *enabled* scan button, or null if none is enabled. */
    bottomBarFirst: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    var path by remember { mutableStateOf(listOf<String>()) }
    var includeSubfolders by remember { mutableStateOf(false) }
    var forceRefetch by remember { mutableStateOf(false) }
    var browserHasFocus by remember { mutableStateOf(false) }
    val gridFocus = remember { GridFocus() }
    val fetchFirst = remember { FocusRequester() }
    // Scan buttons are disabled (unfocusable) while scanning; the fetch options never are.
    val bottomEntry = bottomBarFirst ?: fetchFirst

    val allTags = remember(items) { items.flatMap { it.tags }.distinct().sorted() }
    val hasActiveFilter = filter.isNotBlank() || tagFilter.isNotEmpty()
    fun matches(item: MediaItem): Boolean {
        if (filter.isNotBlank() && !item.name.contains(filter, ignoreCase = true)) return false
        if (tagFilter.isNotEmpty() && item.tags.none { it in tagFilter }) return false
        return true
    }

    val depth = path.size
    val folders: List<String>
    val files: List<MediaItem>
    val fetchScope: List<MediaItem>
    if (hasActiveFilter) {
        folders = emptyList()
        files = items.filter(::matches)
        fetchScope = files
    } else {
        val subfolders = sortedSetOf<String>()
        val filesHere = mutableListOf<MediaItem>()
        val filesHereAndBelow = mutableListOf<MediaItem>()
        for (item in items) {
            val segments = segmentsForItem(item, settings)
            if (segments.size < depth) continue
            if ((0 until depth).any { segments.getOrNull(it) != path[it] }) continue
            filesHereAndBelow += item
            if (segments.size == depth) filesHere += item else subfolders += segments[depth]
        }
        folders = subfolders.toList()
        files = filesHere
        fetchScope = if (includeSubfolders) filesHereAndBelow else filesHere
    }
    val showSubfolderToggle = !hasActiveFilter && folders.isNotEmpty()
    val gridHasTiles = folders.isNotEmpty() || files.isNotEmpty()

    // On a TV, opening a folder (or going back up) would otherwise leave nothing focused, since the
    // tile you clicked is gone — land on the new folder's first tile instead. Same on first load.
    LaunchedEffect(path) {
        gridFocus.state.scrollToItem(0)
        if (isTv) {
            withFrameNanos { }
            gridFocus.focusFirstVisible()
        }
    }
    LaunchedEffect(gridHasTiles) {
        if (isTv && gridHasTiles && !browserHasFocus) {
            withFrameNanos { }
            gridFocus.focusFirstVisible()
        }
    }

    @Composable
    fun RowScope.Crumbs() {
        if (hasActiveFilter || depth == 0) return
        TextButton(onClick = { path = emptyList() }) { Text("Root") }
        path.forEachIndexed { index, segment ->
            Text("/", modifier = Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { path = path.take(index + 1) }, enabled = index != depth - 1) {
                Text(segment)
            }
        }
    }

    Column(modifier.onFocusChanged { browserHasFocus = it.hasFocus }) {
        if (isTv) {
            // One line, so Down from anywhere in it can go straight to the grid's first tile.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        gridFocus.focusFirstVisible() || runCatching { bottomEntry.requestFocus() }.isSuccess
                    } else {
                        false
                    }
                }
            ) {
                filterField()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                ) {
                    Crumbs()
                    tagChips()
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                filterField()
                Crumbs()
                tagChips()
            }
        }
        Spacer(Modifier.height(4.dp))
        MediaGrid(
            items = files,
            allTags = allTags,
            folders = folders,
            onOpenFolder = { name -> path = path + name },
            gridFocus = gridFocus,
            upFromFirstRow = topBarFirst,
            downFromLastRow = bottomEntry,
            onOpen = onOpen,
            onItemUpdated = onItemUpdated,
            onClearInfo = onClearInfo,
            onPickImage = onPickImage,
            onCopy = onCopy,
            copyingItemId = copyingItemId,
            copyProgress = copyProgress,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        // FlowRow, not a plain Row: on a narrow phone (e.g. a folded Galaxy Z Flip) these don't
        // all fit on one line, and a plain Row would squeeze the buttons into unreadable slivers.
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            scanButtons()
            if (showSubfolderToggle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeSubfolders,
                        onCheckedChange = { includeSubfolders = it },
                        modifier = Modifier.focusRequester(fetchFirst)
                    )
                    Text("Include subfolders")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = forceRefetch,
                    onCheckedChange = { forceRefetch = it },
                    modifier = if (showSubfolderToggle) Modifier else Modifier.focusRequester(fetchFirst)
                )
                Text("Force")
            }
            Button(
                onClick = { onFetchInfo(fetchScope.map { it.id }.toSet(), forceRefetch) },
                enabled = !fetchingInfo && fetchScope.isNotEmpty()
            ) {
                Text(if (fetchingInfo) "Fetching artwork & info…" else "Fetch artwork & info", maxLines = 1)
            }
        }
    }
}
