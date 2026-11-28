package com.medialibrary.manager

import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import com.medialibrary.manager.update.AppUpdater
import com.medialibrary.manager.update.UpdateState
import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.medialibrary.manager.ui.MediaLibraryViewModel
import com.medialibrary.manager.ui.components.LocalIsTv
import com.medialibrary.manager.ui.components.PlayerOverlay
import com.medialibrary.manager.ui.screens.DownloadsScreen
import com.medialibrary.manager.ui.screens.LibraryScreen
import com.medialibrary.manager.ui.screens.SettingsScreen
import com.medialibrary.manager.ui.screens.TvScreen
import com.medialibrary.manager.ui.theme.MediaLibraryTheme

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    BottomTab("home", "Home", Icons.Filled.Home),
    BottomTab("folders", "Folders", Icons.Filled.Folder),
    BottomTab("tv", "TV guide", Icons.Filled.Tv),
    BottomTab("downloads", "Downloads", Icons.Filled.Download),
    BottomTab("settings", "Settings", Icons.Filled.Settings)
)

class MainActivity : ComponentActivity() {

    private val viewModel: MediaLibraryViewModel by viewModels()

    /** Set just before launching [pickFolder] when the picker is replacing an existing folder's
     *  URI (Settings' "Change folder…") rather than adding a new one. */
    private var pendingFolderEditId: String? = null

    /** Set just before launching [pickImage]; invoked with the picked image's URI once the photo picker returns. */
    private var pendingImagePick: ((String) -> Unit)? = null

    /** Set just before launching [pickTorrentFile]; gets the picked file's bytes. */
    private var pendingTorrentPick: ((ByteArray) -> Unit)? = null

    private val pickTorrentFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = pendingTorrentPick
        pendingTorrentPick = null
        if (uri == null || callback == null) return@registerForActivityResult
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null) callback(bytes) else viewModel.showMessage("Couldn't read that file.")
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* handled via re-check on scan */ }

    /** Android's built-in photo picker for manually assigning cover art — grants read access to
     *  the returned content:// URI without needing any extra permission. */
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val callback = pendingImagePick
        pendingImagePick = null
        if (uri != null) callback?.invoke(uri.toString())
    }

    /** SAF folder picker backing "which folders are considered" in Settings. */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            pendingFolderEditId = null
            return@registerForActivityResult
        }
        // Read for scanning, write for "Copy to…" and saving downloads into it. Some providers only
        // grant read; that's still fine for browsing.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val editId = pendingFolderEditId
        pendingFolderEditId = null
        if (editId != null) {
            val existing = viewModel.settings.value.localFolders.find { it.id == editId }
            if (existing != null) {
                viewModel.updateLocalFolder(existing.copy(treeUri = uri.toString()))
                return@registerForActivityResult
            }
        }
        val label = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Folder"
        viewModel.addLocalFolder(uri.toString(), label)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = requiredMediaPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
        val isTv = isTelevision()
        // Newer build on GitHub? Ask once per start.
        AppUpdater.checkAtLaunch(this)

        setContent {
            MediaLibraryTheme {
                Surface {
                    CompositionLocalProvider(LocalIsTv provides isTv) {
                        MediaLibraryApp(
                            viewModel = viewModel,
                            isTv = isTv,
                            onPickFolder = { pickFolder.launch(null) },
                            onChangeFolder = { id ->
                                pendingFolderEditId = id
                                pickFolder.launch(null)
                            },
                            onOpenTorrentFile = { onPicked ->
                                pendingTorrentPick = onPicked
                                pickTorrentFile.launch(arrayOf("application/x-bittorrent", "application/octet-stream"))
                            },
                            onPickImage = { onPicked ->
                                pendingImagePick = onPicked
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForeground()
        // Back from allowing "Install unknown apps": carry on with the update.
        if (AppUpdater.state.value is UpdateState.NeedsPermission && packageManager.canRequestPackageInstalls()) {
            AppUpdater.update(this)
        }
    }

    /** Google TV / Android TV. Checks the leanback feature too, since some TV boxes don't report the TV UI mode. */
    private fun isTelevision(): Boolean {
        val uiMode = getSystemService(UiModeManager::class.java)?.currentModeType
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun requiredMediaPermissions(): Array<String> {
        val readPermissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        // Needed to save a network item's download pre-scoped-storage; API 29+ writes to
        // MediaStore.Downloads instead, which needs no extra permission.
        // Progress / "download finished" notifications (Android 13+ asks for this).
        val notify = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        return if (Build.VERSION.SDK_INT < 29) {
            readPermissions + Manifest.permission.WRITE_EXTERNAL_STORAGE + notify
        } else {
            readPermissions + notify
        }
    }
}

@Composable
private fun MediaLibraryApp(
    viewModel: MediaLibraryViewModel,
    isTv: Boolean,
    onPickFolder: () -> Unit,
    onChangeFolder: (String) -> Unit,
    onOpenTorrentFile: (onPicked: (ByteArray) -> Unit) -> Unit,
    onPickImage: (onPicked: (String) -> Unit) -> Unit
) {
    val navController = rememberNavController()
    val playback by viewModel.playback.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    com.medialibrary.manager.ui.components.UpdateDialog()

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Site search lives in the Library (its Search button, on phones and TVs). On a TV the tabs sit in a side rail
    // instead of a bottom bar so the media grid gets most of the screen's height.
    val tabs = TABS
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val goTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current = tabs.firstOrNull { it.route == currentRoute } ?: tabs.first()
    val isLibrary = current.route == "home" || current.route == "folders"

    val content: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(navController = navController, startDestination = "home", modifier = modifier) {
            composable("home") { LibraryScreen(viewModel, isTv, onPickImage, showFolders = false, onOpenTv = { goTo("tv") }) }
            composable("folders") { LibraryScreen(viewModel, isTv, onPickImage, showFolders = true, onOpenTv = { goTo("tv") }) }
            composable("tv") { TvScreen(isTv) }
            composable("downloads") { DownloadsScreen(viewModel, onOpenTorrentFile) }
            composable("settings") { SettingsScreen(viewModel, onPickFolder, onChangeFolder) }
        }
    }

    if (isTv) {
        // TV: the menu is a labelled rail on the left; each screen keeps its own controls.
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                NavigationRail {
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = currentRoute == tab.route,
                            onClick = { goTo(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) }
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        // Phone: a side menu (≡ or swipe from the left) and the same top bar on every screen:
        // ≡, the screen's name, then Search (where there's something to search) and Cast.
        androidx.compose.material3.ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                androidx.compose.material3.ModalDrawerSheet(Modifier.width(260.dp)) {
                    Text(
                        "OmniPlay",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp)
                    )
                    tabs.forEach { tab ->
                        androidx.compose.material3.NavigationDrawerItem(
                            label = { Text(tab.label) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            selected = currentRoute == tab.route,
                            onClick = {
                                goTo(tab.route)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                        Text(
                            current.label,
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (isLibrary) {
                            IconButton(onClick = { viewModel.toggleLibrarySearch() }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                        }
                        com.medialibrary.manager.ui.components.CastButton(viewModel)
                    }
                }
            ) { padding ->
                content(Modifier.padding(padding).fillMaxSize())
            }
        }
    }

    // Live TV plays over any screen (it can be started from Home's "On now" row too).
    val tvController = remember { com.medialibrary.manager.di.ServiceLocator.tvGuide }
    val tvPlaying by tvController.playing.collectAsState()
    tvPlaying?.let { com.medialibrary.manager.ui.components.TvPlayerOverlay(it.stream, it.channelName, tvController::closePlayer) }

    playback?.let { request ->
        PlayerOverlay(
            item = request.item,
            streamUrl = request.streamUrl,
            dataSourceFactory = request.dataSourceFactory,
            isNetwork = request.isNetwork,
            onClose = { viewModel.stopPlayback() }
        )
    }
}
