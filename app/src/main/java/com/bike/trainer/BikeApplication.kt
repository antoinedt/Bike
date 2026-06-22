package com.bike.trainer

import android.app.Application
import com.bike.trainer.di.ServiceLocator

/**
 * Application entry point. Wires up the lightweight [ServiceLocator] that holds
 * the singletons shared across screens (trainer connection, settings, Strava).
 */
class BikeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
