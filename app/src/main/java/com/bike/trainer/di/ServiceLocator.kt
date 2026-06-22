package com.bike.trainer.di

import android.content.Context
import com.bike.trainer.ble.HeartRateManager
import com.bike.trainer.ble.TrainerConnectionManager
import com.bike.trainer.ble.ZwiftClickManager
import com.bike.trainer.data.AppConfigRepository
import com.bike.trainer.data.BackupManager
import com.bike.trainer.data.ProfileRepository
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
    val heartRateManager: HeartRateManager by lazy { HeartRateManager(appContext) }
    val zwiftClickManager: ZwiftClickManager by lazy { ZwiftClickManager(appContext) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext.appDataStore) }
    val appConfigRepository: AppConfigRepository by lazy { AppConfigRepository(appContext.appDataStore) }
    val profileRepository: ProfileRepository by lazy { ProfileRepository(appContext.appDataStore) }
    val stravaRepository: StravaRepository by lazy { StravaRepository(profileRepository) }
    val backupManager: BackupManager by lazy {
        BackupManager(profileRepository, appConfigRepository, settingsRepository)
    }

    /** The ride currently in progress / just finished, shared across screens. */
    @Volatile
    var activeRide: RideEngine? = null

    /** Best in-ride screenshot (auto or manual), shown on the recap screen. */
    @Volatile
    var capturedRideImage: android.graphics.Bitmap? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
