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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private fun iconFor(kind: MediaKind) = when (kind) {
    MediaKind.VIDEO -> "🎬"
    MediaKind.AUDIO -> "🎵"
    MediaKind.IMAGE -> "🖼"
    MediaKind.OTHER -> "📄"
}

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    allTags: List<String>,
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

    if (items.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp)) {
            Text("No media found yet. Try a scan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it.id }) { item ->
            Card(onClick = { onOpen(item) }) {
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
