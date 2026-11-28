package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.medialibrary.manager.model.MediaItem

/**
 * Lets the user correct title/year (auto-fetched values are only a best-effort guess), assign
 * free-form tags, new or picked from [allTags] — the full set already used elsewhere in the
 * library, so tagging stays consistent instead of accumulating near-duplicate spellings — and
 * pick a replacement cover image via [onPickImage] (the Android photo picker), e.g. when "Fetch
 * artwork & info" found nothing and the item has no artwork at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsDialog(
    item: MediaItem,
    allTags: List<String>,
    onPickImage: ((onPicked: (String) -> Unit) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, year: String, tags: List<String>, artworkUrl: String?) -> Unit,
    onClear: () -> Unit,
    /** Looks this one file up again; the progress dialog shows what was found. */
    onFetchInfo: (() -> Unit)? = null
) {
    var title by remember(item.id) { mutableStateOf(item.title ?: "") }
    var year by remember(item.id) { mutableStateOf(item.year ?: "") }
    var tags by remember(item.id) { mutableStateOf(item.tags) }
    var tagInput by remember(item.id) { mutableStateOf("") }
    var artworkUrl by remember(item.id) { mutableStateOf(item.artworkUrl) }

    fun addTag(raw: String) {
        val tag = raw.trim()
        if (tag.isNotEmpty() && tag !in tags) tags = tags + tag
        tagInput = ""
    }

    val suggestions = allTags.filter { it !in tags }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!item.infoFetched) {
                    Text(
                        "No info fetched yet — run \"Fetch artwork & info\" or fill this in yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (artworkUrl == null) {
                    Text(
                        "No artwork found online — add it yourself below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (artworkUrl != null) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.5f)
                    )
                }
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onPickImage != null) {
                        OutlinedButton(onClick = { onPickImage { picked -> artworkUrl = picked } }) {
                            Text("Choose image…")
                        }
                    }
                    if (onFetchInfo != null) {
                        OutlinedButton(onClick = onFetchInfo) { Text("Fetch artwork & info") }
                    }
                }
                AppTextField(
                    title, { title = it },
                    label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                AppTextField(
                    year, { year = it },
                    label = { Text("Year") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (tags.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(tags) { tag ->
                            AssistChip(onClick = { tags = tags - tag }, label = { Text("$tag ✕") })
                        }
                    }
                }
                AppTextField(
                    tagInput, { tagInput = it },
                    label = { Text("Add tag") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag(tagInput) })
                )
                if (suggestions.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(suggestions) { tag ->
                            AssistChip(onClick = { addTag(tag) }, label = { Text(tag) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, year, tags, artworkUrl) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear info") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
