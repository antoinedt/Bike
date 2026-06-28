package com.bike.trainer

import android.app.Application
import com.bike.trainer.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
        // Keep the gear controller's learned button mapping in sync with config.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            ServiceLocator.appConfigRepository.config.collectLatest { cfg ->
                ServiceLocator.zwiftClickManager.setMapping(cfg.gearUpField, cfg.gearDownField)
            }
        }
    }
}
