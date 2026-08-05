package com.medialibrary.manager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medialibrary.manager.di.ServiceLocator
import com.medialibrary.manager.model.AppSettings
import com.medialibrary.manager.model.MediaItem
import com.medialibrary.manager.model.MediaKind
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.model.SearchResultItem
import com.medialibrary.manager.model.SiteProfile
import com.medialibrary.manager.network.SmbContextFactory
import com.medialibrary.manager.network.SmbDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackRequest(
    val item: MediaItem,
    val streamUrl: String,
    val dataSourceFactory: SmbDataSource.Factory
)

class MediaLibraryViewModel : ViewModel() {
    private val settingsRepo = ServiceLocator.settingsRepository
    private val mediaStoreScanner = ServiceLocator.mediaStoreScanner
    private val smbBrowser = ServiceLocator.smbShareBrowser
    private val siteSearch = ServiceLocator.siteSearchRepository
    private val torrentHandoff = ServiceLocator.torrentHandoff

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _localItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val localItems: StateFlow<List<MediaItem>> = _localItems.asStateFlow()

    private val _networkItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val networkItems: StateFlow<List<MediaItem>> = _networkItems.asStateFlow()

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

    private val _shareTestResult = MutableStateFlow<String?>(null)
    val shareTestResult: StateFlow<String?> = _shareTestResult.asStateFlow()

    private val _playback = MutableStateFlow<PlaybackRequest?>(null)
    val playback: StateFlow<PlaybackRequest?> = _playback.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { _settings.value = it }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun scanLocal() {
        viewModelScope.launch {
            _isScanningLocal.value = true
            runCatching { mediaStoreScanner.scan() }
                .onSuccess { _localItems.value = it }
                .onFailure { _errorMessage.value = it.message }
            _isScanningLocal.value = false
        }
    }

    fun scanNetwork() {
        viewModelScope.launch {
            _isScanningNetwork.value = true
            runCatching { _settings.value.networkShares.flatMap { share -> smbBrowser.scan(share) } }
                .onSuccess { _networkItems.value = it }
                .onFailure { _errorMessage.value = it.message }
            _isScanningNetwork.value = false
        }
    }

    fun addShare(share: NetworkShare) = viewModelScope.launch { settingsRepo.addShare(share) }

    fun removeShare(id: String) = viewModelScope.launch { settingsRepo.removeShare(id) }

    fun testShare(share: NetworkShare) {
        viewModelScope.launch {
            _shareTestResult.value = "Testing…"
            val result = smbBrowser.testConnection(share)
            _shareTestResult.value = result.fold({ "Connection OK" }, { "Failed: ${it.message}" })
        }
    }

    fun addSiteProfile(profile: SiteProfile) = viewModelScope.launch { settingsRepo.addSiteProfile(profile) }

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

    fun download(result: SearchResultItem) {
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
            _playback.value = PlaybackRequest(item, streamUrl, factory)
        }
    }

    fun stopPlayback() {
        _playback.value = null
    }
}
