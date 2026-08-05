package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.SearchResultItem

@Composable
fun ResultsList(
    results: List<SearchResultItem>,
    downloadingUrl: String?,
    onDownload: (SearchResultItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (results.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp)) {
            Text("No results yet.")
        }
        return
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        items(results) { result ->
            ListItem(
                headlineContent = { Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text("${result.sourceProfileLabel} · ${result.size.ifBlank { "—" }} · ${result.seeders.ifBlank { "—" }} seeders")
                },
                trailingContent = {
                    val isBusy = downloadingUrl == result.downloadUrl
                    TextButton(onClick = { onDownload(result) }, enabled = !isBusy) {
                        Text(if (isBusy) "Opening…" else "Download")
                    }
                }
            )
            HorizontalDivider()
        }
    }
}
