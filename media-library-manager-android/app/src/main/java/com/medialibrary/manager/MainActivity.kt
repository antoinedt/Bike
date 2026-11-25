package com.medialibrary.manager

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import com.medialibrary.manager.ui.components.PlayerOverlay
import com.medialibrary.manager.ui.screens.LibraryScreen
import com.medialibrary.manager.ui.screens.SearchScreen
import com.medialibrary.manager.ui.screens.SettingsScreen
import com.medialibrary.manager.ui.theme.MediaLibraryTheme

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    BottomTab("library", "Library", Icons.Filled.VideoLibrary),
    BottomTab("search", "Search", Icons.Filled.Search),
    BottomTab("settings", "Settings", Icons.Filled.Settings)
)

class MainActivity : ComponentActivity() {

    private val viewModel: MediaLibraryViewModel by viewModels()

    /** Set just before launching [pickFolder] when the picker is replacing an existing folder's
     *  URI (Settings' "Change folder…") rather than adding a new one. */
    private var pendingFolderEditId: String? = null

    /** Set just before launching [pickImage]; invoked with the picked image's URI once the photo picker returns. */
    private var pendingImagePick: ((String) -> Unit)? = null

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
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
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

        setContent {
            MediaLibraryTheme {
                Surface {
                    MediaLibraryApp(
                        viewModel = viewModel,
                        isTv = isTv,
                        onPickFolder = { pickFolder.launch(null) },
                        onChangeFolder = { id ->
                            pendingFolderEditId = id
                            pickFolder.launch(null)
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
        return if (Build.VERSION.SDK_INT < 29) {
            readPermissions + Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else {
            readPermissions
        }
    }
}

@Composable
private fun MediaLibraryApp(
    viewModel: MediaLibraryViewModel,
    isTv: Boolean,
    onPickFolder: () -> Unit,
    onChangeFolder: (String) -> Unit,
    onPickImage: (onPicked: (String) -> Unit) -> Unit
) {
    val navController = rememberNavController()
    val playback by viewModel.playback.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // On a TV: no site search (nothing to hand a torrent to, and typing queries with a remote is
    // painful), and the tabs sit in a side rail instead of a bottom bar so the media grid gets
    // most of the screen's height.
    val tabs = if (isTv) TABS.filter { it.route != "search" } else TABS
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val goTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!isTv) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { goTo(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (isTv) {
                NavigationRail {
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = currentRoute == tab.route,
                            onClick = { goTo(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
            NavHost(
                navController = navController,
                startDestination = "library",
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                composable("library") { LibraryScreen(viewModel, isTv, onPickImage) }
                if (!isTv) composable("search") { SearchScreen(viewModel) }
                composable("settings") { SettingsScreen(viewModel, onPickFolder, onChangeFolder, showSearchSites = !isTv) }
            }
        }
    }

    playback?.let { request ->
        PlayerOverlay(
            item = request.item,
            streamUrl = request.streamUrl,
            dataSourceFactory = request.dataSourceFactory,
            onClose = { viewModel.stopPlayback() }
        )
    }
}
