package com.medialibrary.manager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medialibrary.manager.di.ServiceLocator
import com.medialibrary.manager.model.AppSettings
import com.medialibrary.manager.model.LocalFolder
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.MediaMetadata
import com.medialibrary.manager.model.MediaSourceType
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.model.ScanProgressEvent
import com.medialibrary.manager.model.SearchResultItem
import com.medialibrary.manager.model.SiteProfile
import com.medialibrary.manager.model.TransferTarget
import com.medialibrary.manager.torrent.TorrentSnapshot
import com.medialibrary.manager.transfer.CopyJob
import com.medialibrary.manager.transfer.CopyState
import com.medialibrary.manager.transfer.Delivery
import com.medialibrary.manager.network.SmbContextFactory
import com.medialibrary.manager.network.SmbDataSource
import com.medialibrary.manager.network.SmbSidecar
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaybackRequest(
    val item: MediaItem,
    val streamUrl: String,
    val dataSourceFactory: DataSource.Factory,
    /** Streams from an SMB share (the player shows network speed for these). */
    val isNetwork: Boolean
)

class MediaLibraryViewModel : ViewModel() {
    private val settingsRepo = ServiceLocator.settingsRepository
    private val mediaStoreScanner = ServiceLocator.mediaStoreScanner
    private val localFolderScanner = ServiceLocator.localFolderScanner
    private val artworkFetcher = ServiceLocator.artworkFetcher
    private val itemCache = ServiceLocator.itemCacheRepository
    private val smbBrowser = ServiceLocator.smbShareBrowser
    private val transfers = ServiceLocator.transferManager
    private val transferStorage = ServiceLocator.transferStorage
    private val remote = ServiceLocator.remoteManager
    private val castManager = ServiceLocator.castManager

    /** The TV chosen with the Cast button (tapping an item plays it there), and what's playing on it. */
    val castTarget: StateFlow<com.medialibrary.manager.cast.CastTarget?> = castManager.target
    val castState: StateFlow<com.medialibrary.manager.cast.CastUiState?> = castManager.state

    suspend fun discoverCastTargets() = castManager.discover()

    fun selectCastTarget(target: com.medialibrary.manager.cast.CastTarget?) = castManager.selectTarget(target)

    fun castItem(item: MediaItem) = castManager.cast(item)

    fun castControl(action: String, value: Double = 0.0) = castManager.control(action, value)

    fun stopCasting() = castManager.stopCasting()
    private val siteSearch = ServiceLocator.siteSearchRepository
    private val torrentHandoff = ServiceLocator.torrentHandoff

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _localItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val localItems: StateFlow<List<MediaItem>> = _localItems.asStateFlow()

    private val _networkItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val networkItems: StateFlow<List<MediaItem>> = _networkItems.asStateFlow()

    /** Local and network items combined into one browsable library (see MediaBrowser's "Local" + per-share top-level grouping). */
    val libraryItems: StateFlow<List<MediaItem>> =
        combine(_localItems, _networkItems) { local, network -> local + network }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    private val _isScanningNetwork = MutableStateFlow(false)
    val isScanningNetwork: StateFlow<Boolean> = _isScanningNetwork.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _downloadingUrl = MutableStateFlow<String?>(null)
    val downloadingUrl: StateFlow<String?> = _downloadingUrl.asStateFlow()

    /** In-app torrent downloads, newest first. */
    val torrents: StateFlow<List<TorrentSnapshot>> = transfers.torrents

    /** Finished downloads being saved to their destination right now, by torrent id. */
    val deliveries: StateFlow<Map<String, Delivery>> = transfers.deliveries

    /** "Copy to…" jobs (running, queued, and finished ones until cleared). */
    val copies: StateFlow<List<CopyJob>> = transfers.copies

    /** The item being copied right now, for the progress overlay on its tile. */
    val copyingItemId: StateFlow<String?> = transfers.copies
        .map { jobs -> jobs.firstOrNull { it.state == CopyState.RUNNING }?.item?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 0f..1f for [copyingItemId]; null while its size is unknown. */
    val copyProgress: StateFlow<Float?> = transfers.copies
        .map { jobs -> jobs.firstOrNull { it.state == CopyState.RUNNING }?.let { if (it.totalBytes > 0) it.copiedBytes.toFloat() / it.totalBytes else null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _addingTorrent = MutableStateFlow(false)
    val addingTorrent: StateFlow<Boolean> = _addingTorrent.asStateFlow()

    private val _shareTestResult = MutableStateFlow<String?>(null)
    val shareTestResult: StateFlow<String?> = _shareTestResult.asStateFlow()

    private val _isFetchingArtwork = MutableStateFlow(false)
    val isFetchingArtwork: StateFlow<Boolean> = _isFetchingArtwork.asStateFlow()

    /** Live, per-item progress for the fetch-progress dialog — see fetchInfo(). Not persisted. */
    private val _scanEvents = MutableStateFlow<List<ScanProgressEvent>>(emptyList())
    val scanEvents: StateFlow<List<ScanProgressEvent>> = _scanEvents.asStateFlow()

    /** Whether the fetch-progress dialog should be shown — stays true after the scan finishes, until dismissScanDialog(). */
    private val _showScanDialog = MutableStateFlow(false)
    val showScanDialog: StateFlow<Boolean> = _showScanDialog.asStateFlow()

    /** Checked by fetchForLibrary before starting each new item; cancelFetchInfo() sets it. Reset at the start of each fetchInfo() call. */
    @Volatile
    private var scanCancelled = false

    private val _playback = MutableStateFlow<PlaybackRequest?>(null)
    val playback: StateFlow<PlaybackRequest?> = _playback.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { _settings.value = it }
        }
        // "Started X on TV" / "Started X, sent from Phone".
        viewModelScope.launch { remote.events.collect { _errorMessage.value = it } }
        // New files landed in a library folder or share (a copy, or a finished download): rescan it.
        viewModelScope.launch {
            transfers.libraryChanged.collect { target ->
                when (target.type) {
                    TransferTarget.FOLDER -> if (!_isScanningLocal.value) scanLocal()
                    TransferTarget.SHARE -> if (!_isScanningNetwork.value) scanNetwork()
                }
            }
        }
    }

    /** Other devices using the same mailbox share, most recently seen first. */
    suspend fun remoteDevices(): List<com.medialibrary.manager.remote.RemoteDevice> = remote.devices()

    /** Starts a download on another device; returns the message to show. */
    suspend fun sendTo(device: com.medialibrary.manager.remote.RemoteDevice, source: String, title: String?): String {
        val name = remote.send(device.id, source, title)
        return "Sent \"$name\" to ${device.name}." + if (device.isOnline()) "" else " It starts next time the app is open there."
    }

    fun updateRemote(deviceName: String, remoteShareId: String) = viewModelScope.launch {
        settingsRepo.updateRemote(deviceName, remoteShareId)
        remote.refreshNow()
    }

    fun thisDeviceName(): String = remote.deviceName(_settings.value)

    fun showMessage(message: String) {
        _errorMessage.value = message
    }

    /** Starts an in-app download from a magnet link, info hash, http(s) .torrent link, or .torrent file bytes. Returns true if it was added. */
    suspend fun addTorrent(source: Any): Boolean {
        _addingTorrent.value = true
        return try {
            val added = transfers.addTorrent(source)
            _errorMessage.value = "Downloading \"${added.name}\" — see Downloads."
            true
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "Couldn't add that download."
            false
        } finally {
            _addingTorrent.value = false
        }
    }

    fun pauseTorrent(id: String) = transfers.pause(id)

    fun resumeTorrent(id: String) = transfers.resume(id)

    fun removeTorrent(id: String, deleteFiles: Boolean) = transfers.remove(id, deleteFiles)

    fun retryTorrentSave(id: String) = transfers.retryDelivery(id)

    fun setTorrentTarget(target: TransferTarget) = viewModelScope.launch { settingsRepo.updateTorrentTarget(target) }

    fun copyItem(item: MediaItem, target: TransferTarget) {
        transfers.copy(item, target)
        _errorMessage.value = "Copying \"${item.name}\" to ${transferStorage.label(target, _settings.value)}…"
    }

    fun cancelCopy(jobId: String) = transfers.cancelCopy(jobId)

    fun clearFinishedCopies() = transfers.clearFinishedCopies()

    fun transferLabel(target: TransferTarget): String = transferStorage.label(target, _settings.value)

    fun canBrowse(target: TransferTarget): Boolean = transferStorage.canBrowse(target)

    suspend fun listFolders(target: TransferTarget): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { transferStorage.listFolders(target, _settings.value) }

    suspend fun createFolder(target: TransferTarget, name: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { transferStorage.createFolder(target, _settings.value, name) }

    /** The app came to the front: (re)start the background service if downloads or copies are running. */
    fun onAppForeground() {
        transfers.start()
        transfers.updateService()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Scoped to the configured folders when there are any; falls back to a whole-device
     * MediaStore scan otherwise. Merges in previously-fetched artwork/title/year from the
     * on-device cache (keyed by each item's stable, path-derived id) — without this, a fresh
     * scan's items (which never carry that data themselves) would silently lose everything
     * "Fetch artwork & info" had already found, on every rescan and every app restart.
     */
    /** [announce]: say how many items were found (when you pressed Scan in Settings). */
    fun scanLocal(announce: Boolean = false) {
        viewModelScope.launch {
            _isScanningLocal.value = true
            val folders = _settings.value.localFolders
            runCatching {
                if (folders.isEmpty()) {
                    mediaStoreScanner.scan()
                } else {
                    folders.flatMap { localFolderScanner.scan(it) }
                }
            }.onSuccess { fresh ->
                val cache = itemCache.load()
                _localItems.value = itemCache.mergeWithCache(fresh, cache)
                if (announce) showMessage("Found ${fresh.size} item${if (fresh.size == 1) "" else "s"}. They're in the Library.")
            }.onFailure { _errorMessage.value = it.message }
            _isScanningLocal.value = false
        }
    }

    /**
     * Fetches title/year/artwork for every not-yet-checked item (win or lose — see
     * MediaItem.infoFetched) within `itemIds`, local and network combined — via iTunes, falling
     * back to TMDb (video, needs an API key from Settings) or MusicBrainz (audio, no key needed)
     * and then (local video only) a frame pulled from the file itself when iTunes has nothing,
     * which is most of a home library's camera footage/home videos and anything outside iTunes'
     * commercial catalog. Pass null to scan the whole library; the
     * folder-scoped "Fetch artwork & info" button in MediaBrowser passes just the ids of whatever
     * folder (optionally + its subfolders) the user is currently browsing. Pass `force: true` to
     * re-fetch every item in scope regardless of whether it was already checked, overriding
     * whatever artwork was found before — the user's manual override for a stuck "no match"
     * result. A title/year the item already has (a previous fetch or a manual edit) is used as
     * the search term and always kept — a re-fetch, forced or not, never overwrites it; only
     * artwork can change, and only under force. Persists results to the on-device cache (so they survive rescans and app restarts) and, for
     * network items, as a sidecar uploaded to the share itself (so any other device sees it too).
     *
     * Shows a progress dialog (scanEvents/showScanDialog) with a live, per-item log — including
     * the per-step reason an item came up with nothing (iTunes: no match, TMDb/MusicBrainz: no
     * match either, no frame to fall back to) rather than a silent pass/fail — that the user can cancel
     * mid-run (cancelFetchInfo()) or dismiss once done (dismissScanDialog()). Cancelling stops any
     * further items from starting but keeps whatever was already found for earlier ones.
     */
    fun fetchInfo(itemIds: Set<String>? = null, force: Boolean = false) {
        viewModelScope.launch {
            _isFetchingArtwork.value = true
            scanCancelled = false
            _scanEvents.value = emptyList()
            _showScanDialog.value = true
            runCatching {
                val all = _localItems.value + _networkItems.value
                val scope = if (itemIds == null) all else all.filter { it.id in itemIds }
                val found = artworkFetcher.fetchForLibrary(
                    scope, _settings.value.networkShares, force,
                    tmdbApiKey = _settings.value.tmdbApiKey,
                    isCancelled = { scanCancelled },
                    onProgress = { event ->
                        _scanEvents.update { prev ->
                            val index = prev.indexOfFirst { it.itemId == event.itemId }
                            if (index >= 0) prev.toMutableList().also { it[index] = event } else prev + event
                        }
                    }
                )
                // title/year are never overwritten here — ArtworkFetcher already searches with
                // (and returns unchanged) whatever title/year the item had going in, so `it.title`/
                // `it.year` only ever differ from `item.title`/`item.year` by filling in a blank.
                fun apply(item: MediaItem) = found[item.id]?.let {
                    item.copy(
                        artworkUrl = if (force) (it.artworkUrl ?: item.artworkUrl) else (item.artworkUrl ?: it.artworkUrl),
                        title = item.title ?: it.title,
                        year = item.year ?: it.year,
                        tags = item.tags.ifEmpty { it.tags },
                        infoFetched = true
                    )
                } ?: item
                _localItems.value = _localItems.value.map(::apply)
                _networkItems.value = _networkItems.value.map(::apply)
                itemCache.save(_localItems.value + _networkItems.value)
            }.onFailure { _errorMessage.value = it.message }
            _isFetchingArtwork.value = false
        }
    }

    /** Stops fetchInfo() from starting any further items; whatever was already found is kept. */
    fun cancelFetchInfo() {
        scanCancelled = true
    }

    /** Hides the fetch-progress dialog. Only meant to be called once the run has finished (or been cancelled). */
    fun dismissScanDialog() {
        _showScanDialog.value = false
    }

    /**
     * Wipes title/year/tags/artworkUrl for one item and resets it to "not yet checked" — lets the
     * user start over on an item that got the wrong auto-fetched info, without forcing a whole
     * rescan. Persisted the same way as every other metadata change: on-device cache (both local
     * and network items) and, for a network item, cleared on its sidecar too.
     */
    fun clearItemMetadata(item: MediaItem) {
        viewModelScope.launch {
            val cleared = item.copy(title = null, year = null, tags = emptyList(), artworkUrl = null, infoFetched = false)
            if (item.sourceType == MediaSourceType.LOCAL) {
                _localItems.value = _localItems.value.map { if (it.id == item.id) cleared else it }
            } else {
                _networkItems.value = _networkItems.value.map { if (it.id == item.id) cleared else it }
                val share = item.shareId?.let { settingsRepo.shareById(it) }
                if (share != null) {
                    val relPath = item.path.removePrefix("smb://${share.id}/")
                    runCatching { SmbSidecar.write(share, relPath, MediaMetadata()) }
                }
            }
            itemCache.save(_localItems.value + _networkItems.value)
        }
    }

    fun addLocalFolder(treeUri: String, label: String) =
        viewModelScope.launch { settingsRepo.addLocalFolder(LocalFolder(treeUri = treeUri, label = label)) }

    fun updateLocalFolder(folder: LocalFolder) = viewModelScope.launch { settingsRepo.updateLocalFolder(folder) }

    fun removeLocalFolder(id: String) = viewModelScope.launch { settingsRepo.removeLocalFolder(id) }

    /** Merges in previously-fetched artwork/title/year from the on-device cache — see scanLocal(). */
    fun scanNetwork(announce: Boolean = false) {
        viewModelScope.launch {
            _isScanningNetwork.value = true
            runCatching { _settings.value.networkShares.flatMap { share -> smbBrowser.scan(share) } }
                .onSuccess { fresh ->
                    val cache = itemCache.load()
                    _networkItems.value = itemCache.mergeWithCache(fresh, cache)
                    if (announce) showMessage("Found ${fresh.size} item${if (fresh.size == 1) "" else "s"}. They're in the Library.")
                }
                .onFailure { _errorMessage.value = it.message }
            _isScanningNetwork.value = false
        }
    }

    fun updateTmdbApiKey(tmdbApiKey: String) = viewModelScope.launch { settingsRepo.updateTmdbApiKey(tmdbApiKey) }

    fun addShare(share: NetworkShare) = viewModelScope.launch { settingsRepo.addShare(share) }

    fun updateShare(share: NetworkShare) = viewModelScope.launch { settingsRepo.updateShare(share) }

    fun removeShare(id: String) = viewModelScope.launch { settingsRepo.removeShare(id) }

    fun testShare(share: NetworkShare) {
        viewModelScope.launch {
            _shareTestResult.value = "Testing…"
            val result = smbBrowser.testConnection(share)
            _shareTestResult.value = result.fold({ "Connection OK" }, { "Failed: ${it.message}" })
        }
    }

    fun addSiteProfile(profile: SiteProfile) = viewModelScope.launch { settingsRepo.addSiteProfile(profile) }

    fun updateSiteProfile(profile: SiteProfile) = viewModelScope.launch { settingsRepo.updateSiteProfile(profile) }

    fun removeSiteProfile(id: String) = viewModelScope.launch { settingsRepo.removeSiteProfile(id) }

    fun search(query: String, profileIds: Set<String>) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isSearching.value = true
            val all = _settings.value.siteProfiles
            val profiles = if (profileIds.isEmpty()) all else all.filter { it.id in profileIds }
            runCatching { siteSearch.searchSites(profiles, query) }
                .onSuccess { _searchResults.value = it }
                .onFailure { _errorMessage.value = it.message }
            _isSearching.value = false
        }
    }

    /** Downloads a search result in the app (Downloads tab). */
    fun download(result: SearchResultItem) {
        viewModelScope.launch {
            _downloadingUrl.value = result.downloadUrl
            addTorrent(result.downloadUrl)
            _downloadingUrl.value = null
        }
    }

    /** Hands a search result to another installed torrent app instead. */
    fun openInOtherApp(result: SearchResultItem) {
        viewModelScope.launch {
            _downloadingUrl.value = result.downloadUrl
            runCatching { torrentHandoff.handOff(result.downloadUrl) }
                .onFailure { _errorMessage.value = it.message }
            _downloadingUrl.value = null
        }
    }

    /** Local items are opened with whatever app the OS has registered for their MIME type — instant, since it's already on-disk. */
    fun openLocalItem(context: Context, item: MediaItem) {
        val mimeType = when (item.kind) {
            MediaKind.VIDEO -> "video/*"
            MediaKind.AUDIO -> "audio/*"
            MediaKind.IMAGE -> "image/*"
            MediaKind.OTHER -> "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.path), mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _errorMessage.value = "No app installed can open this file."
        }
    }

    /** Network items stream live in-app (see SmbDataSource) instead of being handed off. */
    fun playNetworkItem(item: MediaItem) {
        viewModelScope.launch {
            val share = item.shareId?.let { settingsRepo.shareById(it) }
            if (share == null) {
                _errorMessage.value = "Network share for this item is no longer configured."
                return@launch
            }
            val relPath = item.path.removePrefix("smb://${share.id}/")
            val streamUrl = SmbContextFactory.urlFor(share, relPath)
            val factory = SmbDataSource.Factory(SmbContextFactory.contextFor(share))
            _playback.value = PlaybackRequest(item, streamUrl, factory, isNetwork = true)
        }
    }

    /**
     * Local video/audio plays in the same in-app player as network items (full screen, with the
     * diagnostics panel) rather than being handed to another app — a TV often has no app at all
     * that opens a video file.
     */
    @OptIn(UnstableApi::class)
    fun playLocalItem(context: Context, item: MediaItem) {
        _playback.value = PlaybackRequest(item, item.path, DefaultDataSource.Factory(context.applicationContext), isNetwork = false)
    }

    fun stopPlayback() {
        _playback.value = null
    }

    /**
     * Manually overwrites title/year/tags/artworkUrl for one item — unlike the auto fetch, these
     * values take precedence unconditionally, so this is also how you correct a wrong
     * auto-fetched title, or add artwork yourself after a failed fetch (artworkUrl is a picked
     * image's content:// URI, or null to leave whatever artwork the item already has). Also marks
     * the item as fetched, so a failed auto-fetch followed by a manual edit doesn't look like
     * "never checked" and get silently re-attempted. Persisted on-device (ItemCacheRepository,
     * both local and network items) and, for a network item, as a sidecar uploaded to the share.
     */
    fun updateItemMetadata(title: String, year: String, tags: List<String>, artworkUrl: String?, item: MediaItem) {
        viewModelScope.launch {
            val updated = item.copy(
                title = title.ifBlank { null },
                year = year.ifBlank { null },
                tags = tags,
                artworkUrl = artworkUrl ?: item.artworkUrl,
                infoFetched = true
            )
            if (item.sourceType == MediaSourceType.LOCAL) {
                _localItems.value = _localItems.value.map { if (it.id == item.id) updated else it }
            } else {
                _networkItems.value = _networkItems.value.map { if (it.id == item.id) updated else it }
                val share = item.shareId?.let { settingsRepo.shareById(it) }
                if (share != null) {
                    val relPath = item.path.removePrefix("smb://${share.id}/")
                    val merged = MediaMetadata(
                        title = updated.title,
                        year = updated.year,
                        artworkUrl = updated.artworkUrl,
                        tags = tags,
                        infoFetched = true
                    )
                    runCatching { SmbSidecar.write(share, relPath, merged) }
                }
            }
            itemCache.save(_localItems.value + _networkItems.value)
        }
    }
}
