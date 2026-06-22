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
 * User-supplied configuration that can be entered inside the app instead of
 * baked in at build time: Strava OAuth credentials and the MapTiler key for the
 * 3D map. Stored values take precedence; build-time [BuildConfig] values are the
 * fallback so a pre-keyed build still works out of the box.
 */
data class AppConfig(
    val stravaClientId: String,
    val stravaClientSecret: String,
    val mapTilesKey: String,
) {
    val stravaConfigured: Boolean get() = stravaClientId.isNotBlank() && stravaClientSecret.isNotBlank()
    val mapConfigured: Boolean get() = mapTilesKey.isNotBlank()
}

class AppConfigRepository(private val dataStore: DataStore<Preferences>) {

    val config: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            stravaClientId = prefs[STRAVA_CLIENT_ID]?.ifBlank { null } ?: BuildConfig.STRAVA_CLIENT_ID,
            stravaClientSecret = prefs[STRAVA_CLIENT_SECRET]?.ifBlank { null } ?: BuildConfig.STRAVA_CLIENT_SECRET,
            mapTilesKey = prefs[MAP_TILES_KEY]?.ifBlank { null } ?: BuildConfig.MAPTILES_API_KEY,
        )
    }

    suspend fun current(): AppConfig = config.first()

    suspend fun setStravaCredentials(clientId: String, clientSecret: String) {
        dataStore.edit {
            it[STRAVA_CLIENT_ID] = clientId.trim()
            it[STRAVA_CLIENT_SECRET] = clientSecret.trim()
        }
    }

    suspend fun setMapTilesKey(key: String) {
        dataStore.edit { it[MAP_TILES_KEY] = key.trim() }
    }

    private companion object {
        val STRAVA_CLIENT_ID = stringPreferencesKey("cfg_strava_client_id")
        val STRAVA_CLIENT_SECRET = stringPreferencesKey("cfg_strava_client_secret")
        val MAP_TILES_KEY = stringPreferencesKey("cfg_map_tiles_key")
    }
}
