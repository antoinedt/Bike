package com.medialibrary.manager.di

import android.content.Context
import com.medialibrary.manager.artwork.ArtworkFetcher
import com.medialibrary.manager.data.SettingsRepository
import com.medialibrary.manager.library.LocalFolderScanner
import com.medialibrary.manager.library.MediaStoreScanner
import com.medialibrary.manager.network.SmbShareBrowser
import com.medialibrary.manager.search.SiteSearchRepository
import com.medialibrary.manager.torrent.TorrentHandoff

/** Manual singleton wiring, matching the existing Bike app's di/ServiceLocator convention. */
object ServiceLocator {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val mediaStoreScanner: MediaStoreScanner by lazy { MediaStoreScanner(appContext) }
    val localFolderScanner: LocalFolderScanner by lazy { LocalFolderScanner(appContext) }
    val smbShareBrowser: SmbShareBrowser by lazy { SmbShareBrowser() }
    val siteSearchRepository: SiteSearchRepository by lazy { SiteSearchRepository() }
    val torrentHandoff: TorrentHandoff by lazy { TorrentHandoff(appContext) }
    val artworkFetcher: ArtworkFetcher by lazy { ArtworkFetcher() }
}
