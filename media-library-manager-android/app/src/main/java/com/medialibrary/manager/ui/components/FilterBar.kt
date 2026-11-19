package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.MediaItem

/** Tag chips (click to toggle, several combine with OR) and a modified-date range (yyyy-mm-dd), both ANDed together. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBar(
    items: List<MediaItem>,
    selectedTags: List<String>,
    onToggleTag: (String) -> Unit,
    dateFrom: String,
    dateTo: String,
    onDateFromChange: (String) -> Unit,
    onDateToChange: (String) -> Unit
) {
    val allTags = remember(items) { items.flatMap { it.tags }.distinct().sorted() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                dateFrom, onDateFromChange,
                label = { Text("From (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                dateTo, onDateToChange,
                label = { Text("To (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        if (allTags.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(allTags) { tag ->
                    FilterChip(selected = tag in selectedTags, onClick = { onToggleTag(tag) }, label = { Text(tag) })
                }
            }
        }
    }
}
