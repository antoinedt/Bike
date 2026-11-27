package com.medialibrary.manager

import android.app.Application
import com.medialibrary.manager.di.ServiceLocator

class MediaLibraryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.medialibrary.manager.log.AppLog.init(this)
        ServiceLocator.init(this)
        // Picks unfinished downloads back up (and finishes saving any that were mid-save).
        ServiceLocator.transferManager.start()
        // Picks up downloads other devices send here (while the app is running).
        ServiceLocator.remoteManager.start()
    }
}
