package com.bike.trainer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bike.trainer.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Global, app-wide configuration entered in-app. Currently just the MapTiler key
 * for the 3D map (Strava credentials are per-rider, see ProfileRepository).
 * Build-time [BuildConfig] values are the fallback.
 */
data class AppConfig(
    val mapTilesKey: String,
) {
    val mapConfigured: Boolean get() = mapTilesKey.isNotBlank()
}

class AppConfigRepository(private val dataStore: DataStore<Preferences>) {

    val config: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            mapTilesKey = prefs[MAP_TILES_KEY]?.ifBlank { null } ?: BuildConfig.MAPTILES_API_KEY,
        )
    }

    suspend fun current(): AppConfig = config.first()

    suspend fun setMapTilesKey(key: String) {
        dataStore.edit { it[MAP_TILES_KEY] = key.trim() }
    }

    private companion object {
        val MAP_TILES_KEY = stringPreferencesKey("cfg_map_tiles_key")
    }
}
