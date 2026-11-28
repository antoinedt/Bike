package com.medialibrary.manager.ui.screens

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer

import com.medialibrary.manager.ui.components.ActionIcon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.medialibrary.manager.ui.components.AppTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.medialibrary.manager.model.LocalFolder
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.model.SiteProfile
import com.medialibrary.manager.ui.MediaLibraryViewModel

@Composable
fun SettingsScreen(
    viewModel: MediaLibraryViewModel,
    onPickFolder: () -> Unit,
    onChangeFolder: (String) -> Unit,
    /** Shown everywhere now (a TV can download a result itself or send it to another device). */
    showSearchSites: Boolean = true
) {
    val settings by viewModel.settings.collectAsState()
    val shareTestResult by viewModel.shareTestResult.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { VersionLine() }
        item { FoldersSection(settings.localFolders, onPickFolder, onChangeFolder, viewModel) }
        item { HorizontalDivider() }
        item { SharesSection(settings.networkShares, shareTestResult, viewModel) }
        item { HorizontalDivider() }
        item { ExcludedSection(settings, viewModel) }
        item { HorizontalDivider() }
        item { TmdbSection(settings.tmdbApiKey, viewModel) }
        item { HorizontalDivider() }
        item { com.medialibrary.manager.ui.components.ArtworkFetchSection(viewModel) }
        item { HorizontalDivider() }
        item { DownloadsSection(settings, viewModel) }
        item { HorizontalDivider() }
        item { RemoteSection(settings, viewModel) }
        if (showSearchSites) {
            item { HorizontalDivider() }
            item { ProfilesSection(settings.siteProfiles, viewModel) }
        }
    }
}

@Composable
private fun FoldersSection(
    folders: List<LocalFolder>,
    onPickFolder: () -> Unit,
    onChangeFolder: (String) -> Unit,
    viewModel: MediaLibraryViewModel
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local folders", style = MaterialTheme.typography.titleMedium)
        Text(
            "Which folders on this device the Library scan considers. With none added, " +
                "Library scans the whole device via MediaStore instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        folders.forEach { folder ->
            if (editingId == folder.id) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppTextField(
                        label, { label = it },
                        label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        folder.treeUri,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onChangeFolder(folder.id) }) { Text("Change folder…") }
                        TextButton(onClick = {
                            viewModel.updateLocalFolder(folder.copy(label = label))
                            editingId = null
                        }) { Text("Save") }
                        TextButton(onClick = { editingId = null }) { Text("Cancel") }
                    }
                }
            } else {
                ListItem(
                    headlineContent = { Text(folder.label) },
                    trailingContent = {
                        Row {
                            ActionIcon(Icons.Filled.Edit, "Edit", { editingId = folder.id; label = folder.label })
                            ActionIcon(Icons.Filled.Delete, "Remove", { viewModel.removeLocalFolder(folder.id) }, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = onPickFolder) { Text("Add folder…") }
            val scanning by viewModel.isScanningLocal.collectAsState()
            ScanButton("Scan folders", scanning) { viewModel.scanLocal(announce = true) }
        }
    }
}

/** Scans for media (moved here from the Library screen); the Library updates when it's done. */
@Composable
private fun ScanButton(label: String, scanning: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalButton(onClick = onClick, enabled = !scanning) {
        if (scanning) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = null)
        }
        Spacer(Modifier.width(6.dp))
        Text(if (scanning) "Scanning…" else label)
    }
}

@Composable
private fun SharesSection(shares: List<NetworkShare>, testResult: String?, viewModel: MediaLibraryViewModel) {
    // null = no popup; "" = adding a new share; otherwise the id of the share being edited.
    var dialogFor by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local network (SMB shares)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Windows/SMB shares to browse and stream, e.g. a NAS. Credentials are stored locally, unencrypted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        shares.forEach { s ->
            ListItem(
                headlineContent = { Text(s.label.ifBlank { "${s.host}/${s.share}" }) },
                supportingContent = {
                    Text(
                        "smb://${s.host}/${s.share}${if (s.subPath.isNotBlank()) "/${s.subPath}" else ""}" +
                            if (s.backupHost.isNotBlank()) "\naway: ${s.backupHost}" else ""
                    )
                },
                trailingContent = {
                    Row {
                        TextButton(onClick = { viewModel.testShare(s) }) { Text("Test") }
                        ActionIcon(Icons.Filled.Edit, "Edit", { dialogFor = s.id })
                        ActionIcon(Icons.Filled.Delete, "Remove", { viewModel.removeShare(s.id) }, tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialogFor = "" }) { Text("Add share…") }
            if (shares.isNotEmpty()) {
                val scanning by viewModel.isScanningNetwork.collectAsState()
                ScanButton("Scan shares", scanning) { viewModel.scanNetwork(announce = true) }
            }
        }
    }

    dialogFor?.let { id ->
        ShareDialog(
            existing = shares.find { it.id == id },
            testResult = testResult,
            onTest = { viewModel.testShare(it) },
            onSave = { share ->
                if (shares.any { it.id == share.id }) viewModel.updateShare(share) else viewModel.addShare(share)
                dialogFor = null
            },
            onDismiss = { dialogFor = null }
        )
    }
}

/** Add / edit a share, in a popup. */
@Composable
private fun ShareDialog(
    existing: NetworkShare?,
    testResult: String?,
    onTest: (NetworkShare) -> Unit,
    onSave: (NetworkShare) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var backupHost by remember { mutableStateOf(existing?.backupHost ?: "") }
    var share by remember { mutableStateOf(existing?.share ?: "") }
    var subPath by remember { mutableStateOf(existing?.subPath ?: "") }
    var domain by remember { mutableStateOf(existing?.domain?.takeIf { it != "WORKGROUP" } ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var tested by remember { mutableStateOf(false) }

    fun build() = NetworkShare(
        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
        label = label, host = host.trim(), share = share.trim(), subPath = subPath.trim(),
        domain = domain.ifBlank { "WORKGROUP" }, username = username, password = password,
        backupHost = backupHost.trim()
    )

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit \"${existing.label.ifBlank { existing.host }}\"" else "Add a network share") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                AppTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(host, { host = it }, label = { Text("Host / IP (e.g. 192.168.1.28)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(
                    backupHost, { backupHost = it },
                    label = { Text("Away address (optional, e.g. ZeroTier IP)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                AppTextField(share, { share = it }, label = { Text("Share name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(subPath, { subPath = it }, label = { Text("Sub-path (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (tested) testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { tested = true; onTest(build()) }, enabled = host.isNotBlank() && share.isNotBlank()) { Text("Test") }
                TextButton(onClick = { onSave(build()) }, enabled = host.isNotBlank() && share.isNotBlank()) {
                    Text(if (existing != null) "Save" else "Add")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RemoteSection(settings: com.medialibrary.manager.model.AppSettings, viewModel: MediaLibraryViewModel) {
    var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName.ifBlank { viewModel.thisDeviceName() }) }
    var shareId by remember(settings.remoteShareId) { mutableStateOf(settings.remoteShareId) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Send to another device", style = MaterialTheme.typography.titleMedium)
        Text(
            "Start a download on another phone, TV or computer running this app (\"Send to…\" on search results " +
                "and in Downloads), and receive ones sent here. Devices find each other through a hidden folder on a " +
                "network share: pick the same share on every device. Downloads sent here start while the app is open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppTextField(name, { name = it }, label = { Text("This device's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = shareId.isEmpty(), onCheckedChange = { if (it) shareId = "" })
            Text("Off")
        }
        settings.networkShares.forEach { share ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = shareId == share.id, onCheckedChange = { if (it) shareId = share.id })
                Text("Use share: ${share.label}")
            }
        }
        if (settings.networkShares.isEmpty()) {
            Text("Add a network share above first.", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { viewModel.updateRemote(name, shareId) },
            enabled = name.trim() != settings.deviceName.ifBlank { viewModel.thisDeviceName() } || shareId != settings.remoteShareId
        ) { Text("Save") }
    }
}

@Composable
private fun DownloadsSection(settings: com.medialibrary.manager.model.AppSettings, viewModel: MediaLibraryViewModel) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        com.medialibrary.manager.ui.components.FolderPickerDialog(
            title = "Save finished downloads to…",
            confirmLabel = "Save here",
            settings = settings,
            viewModel = viewModel,
            onPick = {
                viewModel.setTorrentTarget(it)
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Torrent downloads", style = MaterialTheme.typography.titleMedium)
        Text(
            "Where finished downloads are saved: this device's Downloads folder, one of your local " +
                "folders, or a network share. Pick a Library folder or share and they show up in the " +
                "Library by themselves.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(viewModel.transferLabel(settings.torrentTarget), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { picking = true }) { Text("Change…") }
    }
}

@Composable
private fun TmdbSection(tmdbApiKey: String, viewModel: MediaLibraryViewModel) {
    var key by remember(tmdbApiKey) { mutableStateOf(tmdbApiKey) }
    var saved by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Movie & TV artwork (TMDb)", style = MaterialTheme.typography.titleMedium)
        Text(
            "When a video item isn't found on iTunes, the app falls back to TMDb (The Movie " +
                "Database) for its poster and release year — a free, official API. Get a free API " +
                "key at themoviedb.org/settings/api and paste it below. Leave this empty to skip " +
                "that fallback. (Music/audio artwork uses MusicBrainz instead, which needs no key.)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppTextField(
            key,
            { key = it; saved = false },
            label = { Text("TMDb API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.updateTmdbApiKey(key); saved = true }, enabled = key != tmdbApiKey) {
                Text("Save")
            }
            if (saved) Text("Saved.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProfilesSection(profiles: List<SiteProfile>, viewModel: MediaLibraryViewModel) {
    // null = no popup; "" = adding a new site; otherwise the id of the site being edited.
    var dialogFor by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Search sites", style = MaterialTheme.typography.titleMedium)
        Text(
            "Configure a site you have the legal right to search. Uses {query} as the search-term " +
                "placeholder and CSS selectors to parse results. Only add sites you're authorized to use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        profiles.forEach { p ->
            ListItem(
                headlineContent = { Text(if (p.useHeadlessBrowser) "${p.label} · JS-rendered" else p.label) },
                supportingContent = { Text(p.searchUrlTemplate) },
                trailingContent = {
                    Row {
                        ActionIcon(Icons.Filled.Edit, "Edit", { dialogFor = p.id })
                        ActionIcon(Icons.Filled.Delete, "Remove", { viewModel.removeSiteProfile(p.id) }, tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
        Button(onClick = { dialogFor = "" }) { Text("Add site…") }
    }

    dialogFor?.let { id ->
        SiteDialog(
            existing = profiles.find { it.id == id },
            onSave = { profile ->
                if (profiles.any { it.id == profile.id }) viewModel.updateSiteProfile(profile) else viewModel.addSiteProfile(profile)
                dialogFor = null
            },
            onDismiss = { dialogFor = null }
        )
    }
}

/** Add / edit a search site, in a popup. */
@Composable
private fun SiteDialog(existing: SiteProfile?, onSave: (SiteProfile) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var searchUrlTemplate by remember { mutableStateOf(existing?.searchUrlTemplate ?: "") }
    var resultItemSelector by remember { mutableStateOf(existing?.resultItemSelector ?: "") }
    var titleSelector by remember { mutableStateOf(existing?.titleSelector ?: "") }
    var linkSelector by remember { mutableStateOf(existing?.linkSelector ?: "") }
    var sizeSelector by remember { mutableStateOf(existing?.sizeSelector ?: "") }
    var seedersSelector by remember { mutableStateOf(existing?.seedersSelector ?: "") }
    var detailPageLinkSelector by remember { mutableStateOf(existing?.detailPageLinkSelector ?: "") }
    var useHeadlessBrowser by remember { mutableStateOf(existing?.useHeadlessBrowser ?: false) }
    val canSave = label.isNotBlank() && searchUrlTemplate.isNotBlank() && resultItemSelector.isNotBlank()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit \"${existing.label}\"" else "Add a search site") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                AppTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(
                    searchUrlTemplate, { searchUrlTemplate = it },
                    label = { Text("Search URL template ({query})") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                AppTextField(resultItemSelector, { resultItemSelector = it }, label = { Text("Result item selector") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(titleSelector, { titleSelector = it }, label = { Text("Title selector") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(linkSelector, { linkSelector = it }, label = { Text("Link selector") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(sizeSelector, { sizeSelector = it }, label = { Text("Size selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(seedersSelector, { seedersSelector = it }, label = { Text("Seeders selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(
                    detailPageLinkSelector, { detailPageLinkSelector = it },
                    label = { Text("Detail-page link selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useHeadlessBrowser, onCheckedChange = { useHeadlessBrowser = it })
                    Text(
                        "Site renders results with JavaScript (slower; only if \"View Page Source\" doesn't show the results)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        SiteProfile(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            label = label,
                            searchUrlTemplate = searchUrlTemplate.trim(),
                            resultItemSelector = resultItemSelector.trim(),
                            titleSelector = titleSelector.trim(),
                            linkSelector = linkSelector.trim(),
                            sizeSelector = sizeSelector.trim(),
                            seedersSelector = seedersSelector.trim(),
                            detailPageLinkSelector = detailPageLinkSelector.trim(),
                            useHeadlessBrowser = useHeadlessBrowser
                        )
                    )
                },
                enabled = canSave
            ) { Text(if (existing != null) "Save" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** "OmniPlay version 0.2.46" (the last number is the build), update status and "Check for updates". */
@Composable
private fun VersionLine() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember { com.medialibrary.manager.update.AppUpdater.currentVersion(context) }
    val update by com.medialibrary.manager.update.AppUpdater.state.collectAsState()
    val status = when (val s = update) {
        com.medialibrary.manager.update.UpdateState.Checking -> "Checking for updates…"
        com.medialibrary.manager.update.UpdateState.UpToDate -> "You have the latest version."
        is com.medialibrary.manager.update.UpdateState.Available -> "${s.version} is available."
        is com.medialibrary.manager.update.UpdateState.Downloading -> "Downloading ${s.version}…"
        is com.medialibrary.manager.update.UpdateState.NeedsPermission -> "Allow installs from OmniPlay to update."
        is com.medialibrary.manager.update.UpdateState.Installing -> "Installing ${s.version}…"
        is com.medialibrary.manager.update.UpdateState.Failed -> s.message
        com.medialibrary.manager.update.UpdateState.Idle -> ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "OmniPlay version $version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        val available = update as? com.medialibrary.manager.update.UpdateState.Available
        var logCleared by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { com.medialibrary.manager.log.AppLog.share(context) }) { Text("Share log") }
            OutlinedButton(onClick = { com.medialibrary.manager.log.AppLog.clear(); logCleared = true }) {
                Text(if (logCleared) "Log cleared" else "Clear log")
            }
        }
        if (available != null) {
            Button(onClick = { com.medialibrary.manager.update.AppUpdater.update(context) }) { Text("Update to ${available.version}") }
        } else {
            OutlinedButton(onClick = { com.medialibrary.manager.update.AppUpdater.check(context) }) { Text("Check for updates") }
        }
    }
}

/** Folders left out of scans: list them, include one again, or show them in the Folders view. */
@Composable
private fun ExcludedSection(settings: com.medialibrary.manager.model.AppSettings, viewModel: MediaLibraryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Excluded folders", style = MaterialTheme.typography.titleMedium)
        Text(
            "In the Library's Folders view, open a folder and tick \"Exclude this folder\" to leave it (and everything in it) out of the library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.showExcludedFolders, onCheckedChange = { viewModel.setShowExcludedFolders(it) })
            Text("Show excluded folders in the Folders view (greyed out), so they can be included again", style = MaterialTheme.typography.bodySmall)
        }
        settings.excludedFolders.forEach { key ->
            val label = com.medialibrary.manager.ui.components.excludedFolderLabel(key, settings)
            ListItem(
                headlineContent = { Text("🚫 $label") },
                trailingContent = { TextButton(onClick = { viewModel.setFolderExcluded(key, false) }) { Text("Include again") } }
            )
        }
    }
}
