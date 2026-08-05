package com.medialibrary.manager

import android.app.Application
import com.medialibrary.manager.di.ServiceLocator

class MediaLibraryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
