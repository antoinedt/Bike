package com.medialibrary.manager.di

import android.content.Context
import com.medialibrary.manager.artwork.ArtworkFetcher
import com.medialibrary.manager.data.ItemCacheRepository
import com.medialibrary.manager.data.SettingsRepository
import com.medialibrary.manager.library.LocalFolderScanner
import com.medialibrary.manager.library.MediaStoreScanner
import com.medialibrary.manager.network.SmbShareBrowser
import com.medialibrary.manager.search.HeadlessWebViewFetcher
import com.medialibrary.manager.search.SiteSearchRepository
import com.medialibrary.manager.torrent.TorrentHandoff
import com.medialibrary.manager.transfer.TransferManager
import com.medialibrary.manager.transfer.TransferStorage

/** Manual singleton wiring, matching the existing Bike app's di/ServiceLocator convention. */
object ServiceLocator {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val itemCacheRepository: ItemCacheRepository by lazy { ItemCacheRepository(appContext) }
    val mediaStoreScanner: MediaStoreScanner by lazy { MediaStoreScanner(appContext) }
    val localFolderScanner: LocalFolderScanner by lazy { LocalFolderScanner(appContext) }
    val smbShareBrowser: SmbShareBrowser by lazy { SmbShareBrowser() }
    val headlessWebViewFetcher: HeadlessWebViewFetcher by lazy { HeadlessWebViewFetcher(appContext) }
    val siteSearchRepository: SiteSearchRepository by lazy { SiteSearchRepository(headlessWebViewFetcher) }
    val torrentHandoff: TorrentHandoff by lazy { TorrentHandoff(appContext) }
    val artworkFetcher: ArtworkFetcher by lazy { ArtworkFetcher(appContext) }
    val transferStorage: TransferStorage by lazy { TransferStorage(appContext) }
    val transferManager: TransferManager by lazy { TransferManager(appContext, settingsRepository, transferStorage) }
    val remoteManager: com.medialibrary.manager.remote.RemoteManager by lazy {
        com.medialibrary.manager.remote.RemoteManager(appContext, settingsRepository, transferManager)
    }
}
