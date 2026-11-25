package com.medialibrary.manager.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.medialibrary.manager.model.MediaItem

/**
 * One chip per tag used anywhere in the library: click to toggle, several selected tags match an
 * item with ANY of them. Emits the chips directly (no layout of its own) so the caller can place
 * them in whatever row they belong to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagFilterChips(
    items: List<MediaItem>,
    selectedTags: List<String>,
    onToggleTag: (String) -> Unit
) {
    val allTags = remember(items) { items.flatMap { it.tags }.distinct().sorted() }
    allTags.forEach { tag ->
        FilterChip(selected = tag in selectedTags, onClick = { onToggleTag(tag) }, label = { Text(tag) })
    }
}
