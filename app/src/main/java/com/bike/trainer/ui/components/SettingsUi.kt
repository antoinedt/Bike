package com.bike.trainer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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

/** Pick the active rider or add a new one (name + weight). */
@Composable
fun ProfileDialog(
    profiles: ProfilesState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: (String, Double) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newWeight by remember { mutableStateOf("75") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Riders") },
        text = {
            Column {
                profiles.entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${entry.profile.name} · ${entry.profile.weightKg.toInt()} kg")
                        TextButton(onClick = { onSelect(entry.profile.id) }) {
                            Text("Select")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Add a rider", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = newWeight,
                    onValueChange = { newWeight = it.filter { c -> c.isDigit() } },
                    label = { Text("Weight (kg)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(newName, newWeight.toDoubleOrNull() ?: 75.0) },
                enabled = newName.isNotBlank(),
            ) { Text("Add rider") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
