package com.medialibrary.manager.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.medialibrary.manager.model.AppSettings
import com.medialibrary.manager.model.LocalFolder
import com.medialibrary.manager.model.NetworkShare
import com.medialibrary.manager.model.SiteProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "media_library_settings")
private val SETTINGS_KEY = stringPreferencesKey("app_settings_json")
private val json = Json { ignoreUnknownKeys = true }

/** DataStore-backed config: network shares and search-site profiles. Nothing is scanned/fetched until added here. */
class SettingsRepository(private val context: Context) {

    val settingsFlow = context.dataStore.data.map { prefs ->
        prefs[SETTINGS_KEY]?.let {
            runCatching { json.decodeFromString<AppSettings>(it) }.getOrDefault(AppSettings())
        } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    private suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val existing = prefs[SETTINGS_KEY]?.let {
                runCatching { json.decodeFromString<AppSettings>(it) }.getOrDefault(AppSettings())
            } ?: AppSettings()
            prefs[SETTINGS_KEY] = json.encodeToString(transform(existing))
        }
    }

    suspend fun addLocalFolder(folder: LocalFolder) = update { it.copy(localFolders = it.localFolders + folder) }

    suspend fun removeLocalFolder(id: String) = update { it.copy(localFolders = it.localFolders.filterNot { f -> f.id == id }) }

    suspend fun addShare(share: NetworkShare) = update { it.copy(networkShares = it.networkShares + share) }

    suspend fun removeShare(id: String) = update { it.copy(networkShares = it.networkShares.filterNot { s -> s.id == id }) }

    suspend fun addSiteProfile(profile: SiteProfile) = update { it.copy(siteProfiles = it.siteProfiles + profile) }

    suspend fun removeSiteProfile(id: String) = update { it.copy(siteProfiles = it.siteProfiles.filterNot { p -> p.id == id }) }

    suspend fun shareById(id: String): NetworkShare? = current().networkShares.find { it.id == id }
}
