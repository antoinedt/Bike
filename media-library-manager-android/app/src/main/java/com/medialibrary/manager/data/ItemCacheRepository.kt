package com.medialibrary.manager.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.medialibrary.manager.model.MediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.itemCacheDataStore by preferencesDataStore(name = "media_library_item_cache")
private val ITEM_CACHE_KEY = stringPreferencesKey("items_json")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Persists fetched artwork/title/year for every item, keyed by its stable id (each scanner now
 * derives id from path, not a random UUID). Without this, a fresh scan's items — which never
 * carry artwork/title/year themselves — would silently lose everything "Fetch artwork & info"
 * had already found, every single time the app reopens or the library is rescanned; there was
 * previously no persistence for scanned items at all on Android.
 */
class ItemCacheRepository(private val context: Context) {

    suspend fun load(): Map<String, MediaItem> =
        context.itemCacheDataStore.data
            .map { prefs ->
                prefs[ITEM_CACHE_KEY]?.let {
                    runCatching { json.decodeFromString<List<MediaItem>>(it).associateBy { item -> item.id } }
                        .getOrDefault(emptyMap())
                } ?: emptyMap()
            }
            .first()

    suspend fun save(items: List<MediaItem>) {
        context.itemCacheDataStore.edit { prefs ->
            prefs[ITEM_CACHE_KEY] = json.encodeToString(items)
        }
    }

    /** Carries forward artworkUrl/title/year for items still present (matched by stable id). */
    fun mergeWithCache(fresh: List<MediaItem>, cache: Map<String, MediaItem>): List<MediaItem> =
        fresh.map { item ->
            val cached = cache[item.id] ?: return@map item
            item.copy(
                artworkUrl = item.artworkUrl ?: cached.artworkUrl,
                title = item.title ?: cached.title,
                year = item.year ?: cached.year
            )
        }
}
