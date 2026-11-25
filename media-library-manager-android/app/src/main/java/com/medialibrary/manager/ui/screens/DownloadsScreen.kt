package com.medialibrary.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.TransferTarget
import com.medialibrary.manager.torrent.TorrentSnapshot
import com.medialibrary.manager.torrent.TorrentState
import com.medialibrary.manager.transfer.CopyJob
import com.medialibrary.manager.transfer.CopyState
import com.medialibrary.manager.transfer.Delivery
import com.medialibrary.manager.transfer.formatBytes
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.AppTextField
import com.medialibrary.manager.ui.components.FolderPickerDialog
import kotlinx.coroutines.launch

/**
 * In-app torrent downloads (paste a link, or open a .torrent file) and "Copy to…" jobs. Downloads
 * stop sharing once complete, then get saved to the folder chosen here (this device's Downloads, a
 * library folder, or a network share).
 */
@Composable
fun DownloadsScreen(viewModel: MediaLibraryViewModel, onOpenTorrentFile: (onPicked: (ByteArray) -> Unit) -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val torrents by viewModel.torrents.collectAsState()
    val deliveries by viewModel.deliveries.collectAsState()
    val copies by viewModel.copies.collectAsState()
    val adding by viewModel.addingTorrent.collectAsState()
    var source by remember { mutableStateOf("") }
    var pickingTarget by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<TorrentSnapshot?>(null) }
    var sendingSource by remember { mutableStateOf<String?>(null) }

    sendingSource?.let { link ->
        com.medialibrary.manager.ui.components.SendToDialog(
            viewModel = viewModel,
            source = link,
            title = null,
            onDismiss = { sendingSource = null },
            onSent = { message ->
                sendingSource = null
                source = ""
                viewModel.showMessage(message)
            }
        )
    }
    val scope = rememberCoroutineScope()

    if (pickingTarget) {
        FolderPickerDialog(
            title = "Save finished downloads to…",
            confirmLabel = "Save here",
            settings = settings,
            viewModel = viewModel,
            onPick = {
                viewModel.setTorrentTarget(it)
                pickingTarget = false
            },
            onDismiss = { pickingTarget = false }
        )
    }
    removing?.let { t -> RemoveDialog(t, onDismiss = { removing = null }) { deleteFiles ->
        viewModel.removeTorrent(t.id, deleteFiles)
        removing = null
    } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(
                        source, { source = it },
                        placeholder = { Text("Paste a magnet link or .torrent link") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = !adding && source.isNotBlank(),
                        onClick = { scope.launch { if (viewModel.addTorrent(source.trim())) source = "" } }
                    ) { Text(if (adding) "Adding…" else "Download") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpenTorrentFile { bytes -> scope.launch { viewModel.addTorrent(bytes) } } }) {
                        Text("Open .torrent file…")
                    }
                    if (settings.remoteShareId.isNotBlank()) {
                        OutlinedButton(enabled = source.isNotBlank(), onClick = { sendingSource = source.trim() }) {
                            Text("Send to…")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Saving to: ${viewModel.transferLabel(settings.torrentTarget)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    TextButton(onClick = { pickingTarget = true }) { Text("Change") }
                }
                Text(
                    "Downloads stop sharing once complete. Only download content you're allowed to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (torrents.isEmpty()) {
            item {
                Text(
                    "No downloads yet. Use Download on a search result, or paste a magnet link above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
        items(torrents, key = { it.id }) { t ->
            TorrentRow(
                t = t,
                saving = deliveries[t.id],
                targetLabel = viewModel.transferLabel(TransferTarget.decode(t.destination)),
                onPause = { viewModel.pauseTorrent(t.id) },
                onResume = { viewModel.resumeTorrent(t.id) },
                onRetrySave = { viewModel.retryTorrentSave(t.id) },
                onRemove = { removing = t }
            )
        }
        if (copies.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Copies", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (copies.any { it.state == CopyState.DONE || it.state == CopyState.FAILED }) {
                        TextButton(onClick = { viewModel.clearFinishedCopies() }) { Text("Clear finished") }
                    }
                }
            }
            items(copies.reversed(), key = { it.id }) { job -> CopyRow(job, onCancel = { viewModel.cancelCopy(job.id) }) }
        }
        item { Row(Modifier.padding(bottom = 16.dp)) {} }
    }
}

@Composable
private fun TorrentRow(
    t: TorrentSnapshot,
    saving: Delivery?,
    targetLabel: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetrySave: () -> Unit,
    onRemove: () -> Unit
) {
    val running = t.state == TorrentState.DOWNLOADING || t.state == TorrentState.METADATA || t.state == TorrentState.CHECKING
    val status = when {
        saving != null -> "Saving to $targetLabel…"
        t.state == TorrentState.DONE && t.delivery == "" -> "Done · saved to $targetLabel"
        t.state == TorrentState.DONE && t.delivery == null -> "Done · saving…"
        t.state == TorrentState.DONE -> t.delivery!!
        t.state == TorrentState.METADATA -> "Getting file list…"
        t.state == TorrentState.CHECKING -> "Checking files…"
        t.state == TorrentState.PAUSED -> "Paused"
        t.state == TorrentState.ERROR -> "Failed: ${t.error}"
        else -> "Downloading"
    }
    val saveFailed = t.state == TorrentState.DONE && !t.delivery.isNullOrEmpty() && saving == null
    val progress = saving?.let { if (it.totalBytes > 0) it.copiedBytes.toFloat() / it.totalBytes else 0f } ?: t.progress
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = when {
                    t.state == TorrentState.ERROR || saveFailed -> MaterialTheme.colorScheme.error
                    t.state == TorrentState.DONE && saving == null -> Color(0xFF6FCE8A)
                    t.state == TorrentState.PAUSED -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (t.state == TorrentState.ERROR || saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            val meta = buildList {
                add("${(t.progress * 100).toInt()}%")
                if (t.totalBytes > 0) add("${formatBytes(t.doneBytes)} of ${formatBytes(t.totalBytes)}")
                if (running) {
                    add("↓ ${formatBytes(t.downloadRate)}/s")
                    add("↑ ${formatBytes(t.uploadRate)}/s")
                    add("${t.peers} peer${if (t.peers == 1) "" else "s"}")
                    t.etaSeconds?.let { add("${formatEta(it)} left") }
                }
            }
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (running) TextButton(onClick = onPause) { Text("Pause") }
                if (t.state == TorrentState.PAUSED || t.state == TorrentState.ERROR) {
                    TextButton(onClick = onResume) { Text(if (t.state == TorrentState.ERROR) "Retry" else "Resume") }
                }
                if (saveFailed) TextButton(onClick = onRetrySave) { Text("Retry saving") }
                if (saving == null) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun CopyRow(job: CopyJob, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.item.title ?: job.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (job.state == CopyState.RUNNING || job.state == CopyState.QUEUED) {
                LinearProgressIndicator(
                    progress = { if (job.totalBytes > 0) job.copiedBytes.toFloat() / job.totalBytes else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val text = when (job.state) {
                CopyState.QUEUED -> "Waiting · to ${job.targetLabel}"
                CopyState.RUNNING -> "Copying to ${job.targetLabel} · ${formatBytes(job.copiedBytes)} of ${formatBytes(job.totalBytes)}"
                CopyState.DONE -> "Copied to ${job.targetLabel}" + (job.savedAs?.takeIf { it != job.item.name }?.let { " as \"$it\"" } ?: "")
                CopyState.FAILED -> "Failed: ${job.error}"
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (job.state == CopyState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            if (job.state == CopyState.RUNNING || job.state == CopyState.QUEUED) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun RemoveDialog(t: TorrentSnapshot, onDismiss: () -> Unit, onConfirm: (deleteFiles: Boolean) -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    val alreadySaved = t.state == TorrentState.DONE && t.delivery == ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove \"${t.name}\"?") },
        text = {
            if (alreadySaved) {
                Text("It's already been saved to its folder; this only removes it from the list.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Text("Also delete what was downloaded so far")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteFiles) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
