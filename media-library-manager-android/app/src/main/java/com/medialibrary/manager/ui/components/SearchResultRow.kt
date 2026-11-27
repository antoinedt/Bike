package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.medialibrary.manager.model.SearchResultItem

/** One search-site result: title, site / size / seeders, and Download, Other app, Send to…. */
@Composable
fun SearchResultRow(
    result: SearchResultItem,
    downloadingUrl: String?,
    /** Downloads in the app (Downloads tab). */
    onDownload: (SearchResultItem) -> Unit,
    /** Hands it to another installed torrent app. */
    onOpenInOtherApp: (SearchResultItem) -> Unit,
    /** "Download on another device"; null when that isn't set up. */
    onSendTo: ((SearchResultItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        ListItem(
            headlineContent = { Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text("${result.sourceProfileLabel} · ${result.size.ifBlank { "—" }} · ${result.seeders.ifBlank { "—" }} seeders")
            },
            trailingContent = {
                val isBusy = downloadingUrl == result.downloadUrl
                Column(horizontalAlignment = Alignment.End) {
                    ActionIcon(Icons.Filled.Download, if (isBusy) "Adding…" else "Download", { onDownload(result) }, enabled = !isBusy)
                    TextButton(onClick = { onOpenInOtherApp(result) }, enabled = !isBusy) {
                        Text("Other app", style = MaterialTheme.typography.labelSmall)
                    }
                    if (onSendTo != null) {
                        TextButton(onClick = { onSendTo(result) }, enabled = !isBusy) {
                            Text("Send to…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        )
        HorizontalDivider()
    }
}
