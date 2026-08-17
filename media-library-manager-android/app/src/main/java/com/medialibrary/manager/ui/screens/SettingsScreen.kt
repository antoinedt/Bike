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
import androidx.compose.material3.OutlinedTextField
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
fun SettingsScreen(viewModel: MediaLibraryViewModel, onPickFolder: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val shareTestResult by viewModel.shareTestResult.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { FoldersSection(settings.localFolders, onPickFolder, viewModel) }
        item { HorizontalDivider() }
        item { SharesSection(settings.networkShares, shareTestResult, viewModel) }
        item { HorizontalDivider() }
        item { ProfilesSection(settings.siteProfiles, viewModel) }
    }
}

@Composable
private fun FoldersSection(folders: List<LocalFolder>, onPickFolder: () -> Unit, viewModel: MediaLibraryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local folders", style = MaterialTheme.typography.titleMedium)
        Text(
            "Which folders on this device the Library scan considers. With none added, " +
                "Library scans the whole device via MediaStore instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        folders.forEach { folder ->
            ListItem(
                headlineContent = { Text(folder.label) },
                trailingContent = { TextButton(onClick = { viewModel.removeLocalFolder(folder.id) }) { Text("Remove") } }
            )
        }
        Button(onClick = onPickFolder) { Text("Add folder…") }
    }
}

@Composable
private fun SharesSection(shares: List<NetworkShare>, testResult: String?, viewModel: MediaLibraryViewModel) {
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var subPath by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
                        TextButton(onClick = { viewModel.removeShare(s.id) }) { Text("Remove") }
                    }
                }
            )
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(host, { host = it }, label = { Text("Host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(share, { share = it }, label = { Text("Share name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subPath, { subPath = it }, label = { Text("Sub-path (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                viewModel.addShare(
                    NetworkShare(
                        label = label,
                        host = host,
                        share = share,
                        subPath = subPath,
                        domain = domain.ifBlank { "WORKGROUP" },
                        username = username,
                        password = password
                    )
                )
                label = ""; host = ""; share = ""; subPath = ""; domain = ""; username = ""; password = ""
            },
            enabled = host.isNotBlank() && share.isNotBlank()
        ) { Text("Add share") }
    }
}

@Composable
private fun ProfilesSection(profiles: List<SiteProfile>, viewModel: MediaLibraryViewModel) {
    var label by remember { mutableStateOf("") }
    var searchUrlTemplate by remember { mutableStateOf("") }
    var resultItemSelector by remember { mutableStateOf("") }
    var titleSelector by remember { mutableStateOf("") }
    var linkSelector by remember { mutableStateOf("") }
    var sizeSelector by remember { mutableStateOf("") }
    var seedersSelector by remember { mutableStateOf("") }
    var detailPageLinkSelector by remember { mutableStateOf("") }
    var useHeadlessBrowser by remember { mutableStateOf(false) }

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
                trailingContent = { TextButton(onClick = { viewModel.removeSiteProfile(p.id) }) { Text("Remove") } }
            )
        }
        OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            searchUrlTemplate, { searchUrlTemplate = it },
            label = { Text("Search URL template ({query})") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            resultItemSelector, { resultItemSelector = it },
            label = { Text("Result item selector") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            titleSelector, { titleSelector = it },
            label = { Text("Title selector") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            linkSelector, { linkSelector = it },
            label = { Text("Link selector") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            sizeSelector, { sizeSelector = it },
            label = { Text("Size selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            seedersSelector, { seedersSelector = it },
            label = { Text("Seeders selector (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
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
        Button(
            onClick = {
                viewModel.addSiteProfile(
                    SiteProfile(
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
                )
                label = ""; searchUrlTemplate = ""; resultItemSelector = ""; titleSelector = ""
                linkSelector = ""; sizeSelector = ""; seedersSelector = ""; detailPageLinkSelector = ""
                useHeadlessBrowser = false
            },
            enabled = label.isNotBlank() && searchUrlTemplate.isNotBlank() && resultItemSelector.isNotBlank()
        ) { Text("Add site") }
    }
}
