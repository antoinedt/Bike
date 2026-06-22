package com.bike.trainer.di

import android.content.Context
import com.bike.trainer.ble.TrainerConnectionManager
import com.bike.trainer.data.SettingsRepository
import com.bike.trainer.data.appDataStore
import com.bike.trainer.session.RideEngine
import com.bike.trainer.strava.StravaRepository

/**
 * Tiny manual dependency container. Holds the process-wide singletons and the
 * in-progress [RideEngine] so it survives navigation between the ride and
 * summary screens.
 */
object ServiceLocator {
    private lateinit var appContext: Context

    val trainerConnection: TrainerConnectionManager by lazy { TrainerConnectionManager(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext.appDataStore) }
    val stravaRepository: StravaRepository by lazy { StravaRepository(appContext.appDataStore) }

    /** The ride currently in progress / just finished, shared across screens. */
    @Volatile
    var activeRide: RideEngine? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
