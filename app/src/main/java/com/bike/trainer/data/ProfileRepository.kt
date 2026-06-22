package com.bike.trainer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Stores rider profiles and their lifetime progression stats as JSON in
 * DataStore. Always guarantees at least one profile exists.
 */
class ProfileRepository(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    val state: Flow<ProfilesState> = dataStore.data.map { prefs ->
        decode(prefs[KEY])
    }

    /** The active profile entry (creating a default first if needed). */
    val active: Flow<ProfileEntry?> = state.map { it.active }

    suspend fun current(): ProfilesState = ensureDefault()

    suspend fun setActive(id: String) = mutate { it.copy(activeId = id) }

    suspend fun addProfile(name: String, weightKg: Double): String {
        val id = UUID.randomUUID().toString()
        val entry = ProfileEntry(RiderProfile(id, name.ifBlank { "Rider" }, weightKg))
        mutate { it.copy(entries = it.entries + entry, activeId = id) }
        return id
    }

    suspend fun updateActiveProfile(transform: (RiderProfile) -> RiderProfile) = mutateActive { entry ->
        entry.copy(profile = transform(entry.profile))
    }

    suspend fun applyRideToActive(summary: RideStatsSummary) = mutateActive { entry ->
        entry.copy(stats = entry.stats.merged(summary))
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun ensureDefault(): ProfilesState {
        val prefs = dataStore.data.first()
        val decoded = decode(prefs[KEY])
        if (decoded.entries.isNotEmpty()) return decoded
        val id = UUID.randomUUID().toString()
        val seeded = ProfilesState(
            entries = listOf(ProfileEntry(RiderProfile(id, "Rider 1", 75.0))),
            activeId = id,
        )
        dataStore.edit { it[KEY] = json.encodeToString(seeded) }
        return seeded
    }

    private suspend fun mutate(transform: (ProfilesState) -> ProfilesState) {
        val currentState = ensureDefault()
        val next = transform(currentState)
        dataStore.edit { it[KEY] = json.encodeToString(next) }
    }

    private suspend fun mutateActive(transform: (ProfileEntry) -> ProfileEntry) = mutate { state ->
        val activeId = state.active?.profile?.id ?: return@mutate state
        state.copy(entries = state.entries.map { if (it.profile.id == activeId) transform(it) else it })
    }

    private fun decode(raw: String?): ProfilesState =
        if (raw.isNullOrBlank()) ProfilesState()
        else runCatching { json.decodeFromString<ProfilesState>(raw) }.getOrElse { ProfilesState() }

    private companion object {
        val KEY = stringPreferencesKey("profiles_state_json")
    }
}
