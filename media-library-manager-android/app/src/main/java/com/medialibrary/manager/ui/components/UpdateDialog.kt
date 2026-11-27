package com.medialibrary.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.update.AppUpdater
import com.medialibrary.manager.update.UpdateState

/** "A new version is available" → download progress → (allow installs) → the system's install screen. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { AppUpdater.dismiss() },
            title = { Text("Update available") },
            text = { Text("OmniPlay ${s.version} is available (you have ${AppUpdater.currentVersion(context)}).") },
            confirmButton = { TextButton(onClick = { AppUpdater.update(context) }) { Text("Update") } },
            dismissButton = { TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") } }
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${s.version}…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(s.progress * 100).toInt()}%")
                }
            },
            confirmButton = {}
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = { AppUpdater.dismiss() },
            title = { Text("Allow OmniPlay to install updates") },
            text = {
                Text(
                    "Android asks once: turn on \"Allow from this source\" for OmniPlay, then come " +
                        "back — the update carries on by itself."
                )
            },
            confirmButton = { TextButton(onClick = { AppUpdater.openInstallPermission(context) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { AppUpdater.dismiss() }) { Text("Later") } }
        )
        else -> Unit
    }
}
