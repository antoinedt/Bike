package com.medialibrary.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaSourceType
import kotlin.math.ln
import kotlin.math.pow

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / 1024.0.pow(group)
    return "%.${if (group == 0) 0 else 1}f %s".format(value, units[group])
}

/**
 * The grid's scroll state plus what a TV remote needs from it: which tile has focus, and a focus
 * handle for each tile currently on screen. Only on-screen tiles are registered — a tile scrolled
 * out of a lazy grid no longer exists, so it can't be a focus target.
 */
class GridFocus {
    val state = LazyGridState()
    internal val tiles = mutableMapOf<Int, FocusRequester>()
    internal var focusedIndex = -1

    /** Focuses the top-left tile currently on screen. False if there's none (empty grid). */
    fun focusFirstVisible(): Boolean {
        val requester = tiles[state.firstVisibleItemIndex] ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }
}

@Composable
private fun rememberTileModifier(gridFocus: GridFocus, index: Int): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(index, requester) {
        gridFocus.tiles[index] = requester
        onDispose { if (gridFocus.tiles[index] === requester) gridFocus.tiles.remove(index) }
    }
    return Modifier
        .focusRequester(requester)
        .onFocusChanged { if (it.hasFocus) gridFocus.focusedIndex = index }
}

private fun iconFor(kind: MediaKind) = when (kind) {
    MediaKind.VIDEO -> "🎬"
    MediaKind.AUDIO -> "🎵"
    MediaKind.IMAGE -> "🖼"
    MediaKind.OTHER -> "📄"
}

/**
 * Subfolders (as 📁 tiles, first) and files. With a TV remote, Up from the top row goes to
 * [upFromFirstRow] and Down from the bottom row to [downFromLastRow] — the first control of the bar
 * above/below — rather than whichever control happens to be nearest the tile.
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    allTags: List<String>,
    folders: List<String> = emptyList(),
    onOpenFolder: (String) -> Unit = {},
    gridFocus: GridFocus = remember { GridFocus() },
    upFromFirstRow: FocusRequester? = null,
    downFromLastRow: FocusRequester? = null,
    onOpen: (MediaItem) -> Unit,
    onItemUpdated: (title: String, year: String, tags: List<String>, artworkUrl: String?, item: MediaItem) -> Unit,
    onClearInfo: (MediaItem) -> Unit,
    onPickImage: ((onPicked: (String) -> Unit) -> Unit)? = null,
    onDownload: ((MediaItem) -> Unit)? = null,
    downloadingItemId: String? = null,
    /** 0f..1f for the item currently downloading; null while its size is unknown (shows an indeterminate spinner instead of a percentage). */
    downloadProgress: Float? = null,
    modifier: Modifier = Modifier
) {
    var editingItem by remember { mutableStateOf<MediaItem?>(null) }

    if (items.isEmpty() && folders.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp)) {
            Text("No media found yet. Try a scan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        state = gridFocus.state,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val layout = gridFocus.state.layoutInfo
            val focused = layout.visibleItemsInfo.firstOrNull { it.index == gridFocus.focusedIndex }
                ?: return@onPreviewKeyEvent false
            val target = when (event.key) {
                Key.DirectionUp -> upFromFirstRow.takeIf { focused.row == 0 }
                Key.DirectionDown -> {
                    val lastTile = layout.visibleItemsInfo.firstOrNull { it.index == layout.totalItemsCount - 1 }
                    downFromLastRow.takeIf { lastTile != null && lastTile.row == focused.row }
                }
                else -> null
            } ?: return@onPreviewKeyEvent false
            runCatching { target.requestFocus() }
            true
        },
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(folders, key = { _, name -> "folder:$name" }) { index, name ->
            Card(onClick = { onOpenFolder(name) }, modifier = rememberTileModifier(gridFocus, index)) {
                Column(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📁", style = MaterialTheme.typography.headlineMedium)
                    Box(Modifier.height(4.dp))
                    Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        itemsIndexed(items, key = { _, item -> item.id }) { fileIndex, item ->
            Card(onClick = { onOpen(item) }, modifier = rememberTileModifier(gridFocus, folders.size + fileIndex)) {
                Box {
                    Column(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (item.artworkUrl != null) {
                            AsyncImage(
                                model = item.artworkUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            Text(iconFor(item.kind), style = MaterialTheme.typography.headlineMedium)
                        }
                        Box(Modifier.height(4.dp))
                        Text(
                            item.title ?: item.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            (item.year?.let { "$it · " } ?: "") + "${item.extension.uppercase()} · ${formatSize(item.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { editingItem = item },
                        modifier = Modifier.align(Alignment.TopStart).size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "Edit info",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (onDownload != null && item.sourceType == MediaSourceType.NETWORK) {
                        if (item.id == downloadingItemId) {
                            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (downloadProgress != null) {
                                        CircularProgressIndicator(
                                            progress = { downloadProgress },
                                            modifier = Modifier.size(32.dp),
                                            color = Color.White,
                                            strokeWidth = 3.dp
                                        )
                                        Text(
                                            "${(downloadProgress * 100).toInt()}%",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    } else {
                                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                                    }
                                }
                            }
                        } else {
                            IconButton(
                                onClick = { onDownload(item) },
                                modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = "Download",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        MediaDetailsDialog(
            item = item,
            allTags = allTags,
            onPickImage = onPickImage,
            onDismiss = { editingItem = null },
            onSave = { title, year, tags, artworkUrl ->
                onItemUpdated(title, year, tags, artworkUrl, item)
                editingItem = null
            },
            onClear = {
                onClearInfo(item)
                editingItem = null
            }
        )
    }
}
