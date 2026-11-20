package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.ScanLogEntry
import com.medialibrary.manager.model.ScanProgressEvent

private fun stepIcon(result: String) = when (result) {
    "found" -> "✓"
    "not-found" -> "—"
    else -> "⚠"
}

private fun stepText(entry: ScanLogEntry) = when (entry.result) {
    "found" -> "found"
    "not-found" -> entry.detail ?: "no match"
    else -> "error — ${entry.detail}"
}

/**
 * Live progress for a "Fetch artwork & info" run: one block per item, updated in place as events
 * arrive (started -> done/cached/error), with the full per-step log (iTunes/Google search/Video
 * frame, each found/not-found/error) shown for a "done" item — this is the answer to "why didn't
 * this one get artwork": every lookup that was tried and what it returned, not just a final
 * yes/no. Stays open after the scan finishes so that log is still there to read; Cancel is only
 * offered while running, OK only once it's done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanProgressDialog(
    running: Boolean,
    events: List<ScanProgressEvent>,
    onCancel: () -> Unit,
    onClose: () -> Unit
) {
    val doneCount = events.count { it.status != "started" }
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = {
            Text(if (events.isEmpty()) "Fetch artwork & info" else "Fetch artwork & info ($doneCount/${events.size})")
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (events.isEmpty()) {
                    Text("Starting…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                events.forEach { event ->
                    Column {
                        Text(event.itemName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        when (event.status) {
                            "started" -> Text(
                                "Working…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            "cached" -> Text(
                                "Used a previous result — no new lookup needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            "error" -> Text(
                                "⚠ ${event.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            "done" -> event.log.forEach { entry ->
                                Text(
                                    "${stepIcon(entry.result)} ${entry.step}: ${stepText(entry)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!running) {
                TextButton(onClick = onClose) { Text("OK") }
            }
        },
        dismissButton = {
            if (running) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}
