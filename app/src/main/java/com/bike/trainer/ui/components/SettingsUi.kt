package com.bike.trainer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bike.trainer.data.ProfilesState
import com.bike.trainer.data.StravaAccount

/** A device row with an icon, name, status line and a Connect/Manage action. */
@Composable
fun DeviceRow(
    icon: ImageVector,
    name: String,
    status: String,
    connected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, contentDescription = null,
                tint = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(name, fontWeight = FontWeight.Medium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onClick) { Text(if (connected) "Manage" else "Connect") }
    }
}

/** Pick the active rider, open the add-rider page, or remove a rider. */
@Composable
fun ProfileDialog(
    profiles: ProfilesState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: (String, Double) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    // The rider (id to name) awaiting a delete confirmation, if any.
    var confirmRemove by remember { mutableStateOf<Pair<String, String>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Riders") },
        text = {
            Column {
                if (profiles.entries.isEmpty()) {
                    Text(
                        "No riders yet — add one to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                profiles.entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.profile.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onSelect(entry.profile.id) }) {
                            Text("Select")
                        }
                        IconButton(onClick = { confirmRemove = entry.profile.id to entry.profile.name }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove ${entry.profile.name}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showAdd = true }) { Text("Add rider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    if (showAdd) {
        AddRiderDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, weight ->
                showAdd = false
                onAdd(name, weight)
            },
        )
    }

    confirmRemove?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove $name?") },
            text = { Text("This deletes $name and their stats. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(id)
                    confirmRemove = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

/** Add-a-rider page: name + weight, with Add / Cancel. */
@Composable
private fun AddRiderDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("75") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add rider") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter { c -> c.isDigit() } },
                    label = { Text("Weight (kg)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name.trim(), weight.toDoubleOrNull() ?: 75.0) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Single-field key entry dialog (e.g. MapTiler key). */
@Composable
fun KeyDialog(
    title: String,
    label: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Strava API key entry for the active rider. */
@Composable
fun StravaKeysDialog(
    initial: StravaAccount,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var id by remember { mutableStateOf(initial.clientId) }
    var secret by remember { mutableStateOf(initial.clientSecret) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Strava API keys") },
        text = {
            Column {
                Text(
                    "Create an app at strava.com/settings/api and set the Authorization " +
                        "Callback Domain to: strava-auth",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Client ID") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text("Client Secret") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(id, secret) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
