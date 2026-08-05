package com.medialibrary.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.ResultsList

@Composable
fun SearchScreen(viewModel: MediaLibraryViewModel) {
    val settings by viewModel.settings.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val searching by viewModel.isSearching.collectAsState()
    val downloadingUrl by viewModel.downloadingUrl.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (settings.siteProfiles.isEmpty()) {
            Text("No search sites configured yet. Add one in Settings first.")
            return@Column
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(settings.siteProfiles) { profile ->
                FilterChip(
                    selected = profile.id in selected,
                    onClick = { selected = if (profile.id in selected) selected - profile.id else selected + profile.id },
                    label = { Text(profile.label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (selected.isEmpty()) "Searching all configured sites" else "Searching ${selected.size} site(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search term") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { viewModel.search(query, selected) }, enabled = !searching && query.isNotBlank()) {
                Text(if (searching) "Searching…" else "Search")
            }
        }
        Spacer(Modifier.height(12.dp))
        ResultsList(
            results = results,
            downloadingUrl = downloadingUrl,
            onDownload = { viewModel.download(it) },
            modifier = Modifier.weight(1f)
        )
    }
}
