package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.MediaItem

/**
 * Tag chips (click to toggle, several combine with OR) and a modified-date range (yyyy-mm-dd), both
 * ANDed together. The focus requesters route the D-pad between rows: Up/Down from the date fields
 * always lands on the first control of the row above/below (see LibraryScreen).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBar(
    items: List<MediaItem>,
    selectedTags: List<String>,
    onToggleTag: (String) -> Unit,
    dateFrom: String,
    dateTo: String,
    onDateFromChange: (String) -> Unit,
    onDateToChange: (String) -> Unit,
    dateFromFocusRequester: FocusRequester = remember { FocusRequester() },
    aboveFocusRequester: FocusRequester = FocusRequester.Default,
    belowFocusRequester: FocusRequester = FocusRequester.Default
) {
    val allTags = remember(items) { items.flatMap { it.tags }.distinct().sorted() }
    val firstTagRequester = remember { FocusRequester() }
    val dateNav = Modifier.focusProperties {
        up = aboveFocusRequester
        down = if (allTags.isNotEmpty()) firstTagRequester else belowFocusRequester
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                dateFrom, onDateFromChange,
                label = { Text("From (yyyy-mm-dd)") }, singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(dateFromFocusRequester).then(dateNav)
            )
            OutlinedTextField(
                dateTo, onDateToChange,
                label = { Text("To (yyyy-mm-dd)") }, singleLine = true,
                modifier = Modifier.weight(1f).then(dateNav)
            )
        }
        if (allTags.isNotEmpty()) {
            // Wraps (FlowRow) rather than scrolling sideways (LazyRow): every chip stays on screen,
            // and the first chip always exists as a D-pad target from the date fields above.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                allTags.forEachIndexed { index, tag ->
                    FilterChip(
                        selected = tag in selectedTags,
                        onClick = { onToggleTag(tag) },
                        label = { Text(tag) },
                        modifier = if (index == 0) Modifier.focusRequester(firstTagRequester) else Modifier
                    )
                }
            }
        }
    }
}
