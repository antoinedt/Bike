package com.medialibrary.manager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.AppSettings
import com.medialibrary.manager.model.TransferTarget
import com.medialibrary.manager.ui.MediaLibraryViewModel
import kotlinx.coroutines.launch

/**
 * Pick a place to put files: first one of the locations the app knows (this device's Downloads,
 * each configured local folder, each network share), then optionally a folder inside it, with a
 * way to create a new one. Used by "Copy to…" and by the download-destination setting.
 */
@Composable
fun FolderPickerDialog(
    title: String,
    confirmLabel: String,
    settings: AppSettings,
    viewModel: MediaLibraryViewModel,
    onPick: (TransferTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<TransferTarget?>(null) }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(current, reload) {
        val target = current
        folders = emptyList()
        error = null
        if (target == null || !viewModel.canBrowse(target)) return@LaunchedEffect
        loading = true
        runCatching { viewModel.listFolders(target) }
            .onSuccess { folders = it }
            .onFailure { error = it.message ?: "Couldn't open this folder." }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val target = current
                if (target == null) {
                    Text("Where to?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val locations = buildList {
                        add(TransferTarget())
                        settings.localFolders.forEach { add(TransferTarget(TransferTarget.FOLDER, it.id)) }
                        settings.networkShares.forEach { add(TransferTarget(TransferTarget.SHARE, it.id)) }
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(locations) { location ->
                            ListItem(
                                headlineContent = { Text(viewModel.transferLabel(location), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingContent = {
                                    Icon(
                                        when (location.type) {
                                            TransferTarget.SHARE -> Icons.Filled.Storage
                                            TransferTarget.FOLDER -> Icons.Filled.Folder
                                            else -> Icons.Filled.PhoneAndroid
                                        },
                                        contentDescription = null
                                    )
                                },
                                modifier = Modifier.clickable { current = location }
                            )
                        }
                    }
                } else {
                    Text(viewModel.transferLabel(target), style = MaterialTheme.typography.titleSmall)
                    ListItem(
                        headlineContent = { Text(if (target.relPath.isEmpty()) "All locations" else "Up one folder") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                        modifier = Modifier.clickable {
                            creating = false
                            current = if (target.relPath.isEmpty()) null else target.parent()
                        }
                    )
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Loading folders…")
                        }
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        !viewModel.canBrowse(target) -> Text(
                            "Files go straight into this folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(folders) { name ->
                                ListItem(
                                    headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                    modifier = Modifier.clickable { current = target.child(name) }
                                )
                            }
                        }
                    }
                    if (viewModel.canBrowse(target) && error == null) {
                        if (creating) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppTextField(
                                    newName, { newName = it },
                                    placeholder = { Text("New folder name") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    enabled = newName.isNotBlank(),
                                    onClick = {
                                        scope.launch {
                                            runCatching { viewModel.createFolder(target, newName.trim()) }
                                                .onSuccess {
                                                    creating = false
                                                    current = target.child(newName.trim())
                                                    newName = ""
                                                }
                                                .onFailure { error = it.message ?: "Couldn't create the folder." }
                                        }
                                    }
                                ) { Text("Create") }
                            }
                        } else {
                            TextButton(onClick = { creating = true }) { Text("New folder…") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { current?.let(onPick) }, enabled = current != null && error == null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
