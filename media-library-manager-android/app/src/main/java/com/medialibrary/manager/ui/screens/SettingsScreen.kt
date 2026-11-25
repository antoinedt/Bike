package com.medialibrary.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
    /** False on a TV, which has no Search tab to use these with. */
    showSearchSites: Boolean = true
) {
    val settings by viewModel.settings.collectAsState()
    val shareTestResult by viewModel.shareTestResult.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { FoldersSection(settings.localFolders, onPickFolder, onChangeFolder, viewModel) }
        item { HorizontalDivider() }
        item { SharesSection(settings.networkShares, shareTestResult, viewModel) }
        item { HorizontalDivider() }
        item { TmdbSection(settings.tmdbApiKey, viewModel) }
        item { HorizontalDivider() }
        item { DownloadsSection(settings, viewModel) }
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
                            TextButton(onClick = { editingId = folder.id; label = folder.label }) { Text("Edit") }
                            TextButton(onClick = { viewModel.removeLocalFolder(folder.id) }) { Text("Remove") }
                        }
                    }
                )
            }
        }
        Button(onClick = onPickFolder) { Text("Add folder…") }
    }
}

@Composable
private fun SharesSection(shares: List<NetworkShare>, testResult: String?, viewModel: MediaLibraryViewModel) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var subPath by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun clearForm() {
        label = ""; host = ""; share = ""; subPath = ""; domain = ""; username = ""; password = ""
    }

    fun startEdit(s: NetworkShare) {
        editingId = s.id
        label = s.label; host = s.host; share = s.share; subPath = s.subPath
        domain = s.domain; username = s.username; password = s.password
    }

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
                    Text("smb://${s.host}/${s.share}${if (s.subPath.isNotBlank()) "/${s.subPath}" else ""}")
                },
                trailingContent = {
                    Row {
                        TextButton(onClick = { viewModel.testShare(s) }) { Text("Test") }
                        TextButton(onClick = { startEdit(s) }) { Text("Edit") }
                        TextButton(onClick = {
                            if (s.id == editingId) { editingId = null; clearForm() }
                            viewModel.removeShare(s.id)
                        }) { Text("Remove") }
                    }
                }
            )
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (editingId != null) {
            Text("Editing \"${label.ifBlank { host }}\" — Save changes to apply, or Cancel.", style = MaterialTheme.typography.bodySmall)
        }

        AppTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        AppTextField(host, { host = it }, label = { Text("Host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val id = editingId
                    if (id != null) {
                        viewModel.updateShare(
                            NetworkShare(
                                id = id, label = label, host = host, share = share, subPath = subPath,
                                domain = domain.ifBlank { "WORKGROUP" }, username = username, password = password
                            )
                        )
                    } else {
                        viewModel.addShare(
                            NetworkShare(
                                label = label, host = host, share = share, subPath = subPath,
                                domain = domain.ifBlank { "WORKGROUP" }, username = username, password = password
                            )
                        )
                    }
                    editingId = null
                    clearForm()
                },
                enabled = host.isNotBlank() && share.isNotBlank()
            ) { Text(if (editingId != null) "Save changes" else "Add share") }
            if (editingId != null) {
                TextButton(onClick = { editingId = null; clearForm() }) { Text("Cancel") }
            }
        }
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
                "Library by themselves. Downloads stop sharing once complete.",
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
    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var searchUrlTemplate by remember { mutableStateOf("") }
    var resultItemSelector by remember { mutableStateOf("") }
    var titleSelector by remember { mutableStateOf("") }
    var linkSelector by remember { mutableStateOf("") }
    var sizeSelector by remember { mutableStateOf("") }
    var seedersSelector by remember { mutableStateOf("") }
    var detailPageLinkSelector by remember { mutableStateOf("") }
    var useHeadlessBrowser by remember { mutableStateOf(false) }

    fun clearForm() {
        label = ""; searchUrlTemplate = ""; resultItemSelector = ""; titleSelector = ""
        linkSelector = ""; sizeSelector = ""; seedersSelector = ""; detailPageLinkSelector = ""
        useHeadlessBrowser = false
    }

    fun startEdit(p: SiteProfile) {
        editingId = p.id
        label = p.label; searchUrlTemplate = p.searchUrlTemplate; resultItemSelector = p.resultItemSelector
        titleSelector = p.titleSelector; linkSelector = p.linkSelector; sizeSelector = p.sizeSelector
        seedersSelector = p.seedersSelector; detailPageLinkSelector = p.detailPageLinkSelector
        useHeadlessBrowser = p.useHeadlessBrowser
    }

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
                        TextButton(onClick = { startEdit(p) }) { Text("Edit") }
                        TextButton(onClick = {
                            if (p.id == editingId) { editingId = null; clearForm() }
                            viewModel.removeSiteProfile(p.id)
                        }) { Text("Remove") }
                    }
                }
            )
        }
        if (editingId != null) {
            Text("Editing \"$label\" — Save changes to apply, or Cancel.", style = MaterialTheme.typography.bodySmall)
        }
        AppTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        AppTextField(
            searchUrlTemplate, { searchUrlTemplate = it },
            label = { Text("Search URL template ({query})") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        AppTextField(
            resultItemSelector, { resultItemSelector = it },
            label = { Text("Result item selector") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        AppTextField(
            titleSelector, { titleSelector = it },
            label = { Text("Title selector") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        AppTextField(
            linkSelector, { linkSelector = it },
            label = { Text("Link selector") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        AppTextField(
            sizeSelector, { sizeSelector = it },
            label = { Text("Size selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        AppTextField(
            seedersSelector, { seedersSelector = it },
            label = { Text("Seeders selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        AppTextField(
            detailPageLinkSelector, { detailPageLinkSelector = it },
            label = { Text("Detail-page link selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useHeadlessBrowser, onCheckedChange = { useHeadlessBrowser = it })
            Text(
                "Site renders results with JavaScript (use an off-screen WebView to fetch it — " +
                    "slower, but works when \"View Page Source\" doesn't show the results)",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val id = editingId
                    val profile = SiteProfile(
                        id = id ?: java.util.UUID.randomUUID().toString(),
                        label = label,
                        searchUrlTemplate = searchUrlTemplate,
                        resultItemSelector = resultItemSelector,
                        titleSelector = titleSelector,
                        linkSelector = linkSelector,
                        sizeSelector = sizeSelector,
                        seedersSelector = seedersSelector,
                        detailPageLinkSelector = detailPageLinkSelector,
                        useHeadlessBrowser = useHeadlessBrowser
                    )
                    if (id != null) viewModel.updateSiteProfile(profile) else viewModel.addSiteProfile(profile)
                    editingId = null
                    clearForm()
                },
                enabled = label.isNotBlank() && searchUrlTemplate.isNotBlank() && resultItemSelector.isNotBlank()
            ) { Text(if (editingId != null) "Save changes" else "Add site") }
            if (editingId != null) {
                TextButton(onClick = { editingId = null; clearForm() }) { Text("Cancel") }
            }
        }
    }
}
