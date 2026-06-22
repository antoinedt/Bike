package com.bike.trainer

import android.app.Application
import com.bike.trainer.di.ServiceLocator
import org.maplibre.android.MapLibre

/**
 * Application entry point. Wires up the lightweight [ServiceLocator] that holds
 * the singletons shared across screens (trainer connection, settings, Strava),
 * and initialises the MapLibre map engine used for the 3D ride view.
 */
class BikeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // No Mapbox token needed; tiles come from MapTiler / the MapLibre demo.
        MapLibre.getInstance(this)
    }
}
