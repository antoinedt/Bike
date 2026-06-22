package com.bike.trainer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bike.trainer.route.RouteGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Rider profile + ride preferences. */
data class RiderSettings(
    val riderMassKg: Double = 75.0,
    val gearCount: Int = 12,
    val difficulty: RouteGenerator.Difficulty = RouteGenerator.Difficulty.ROLLING,
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<RiderSettings> = dataStore.data.map { prefs ->
        RiderSettings(
            riderMassKg = prefs[RIDER_MASS] ?: 75.0,
            gearCount = prefs[GEAR_COUNT] ?: 12,
            difficulty = prefs[DIFFICULTY]?.let { runCatching { RouteGenerator.Difficulty.valueOf(it) }.getOrNull() }
                ?: RouteGenerator.Difficulty.ROLLING,
        )
    }

    suspend fun setRiderMass(massKg: Double) {
        dataStore.edit { it[RIDER_MASS] = massKg.coerceIn(35.0, 200.0) }
    }

    suspend fun setGearCount(count: Int) {
        dataStore.edit { it[GEAR_COUNT] = count.coerceIn(1, 24) }
    }

    suspend fun setDifficulty(difficulty: RouteGenerator.Difficulty) {
        dataStore.edit { it[DIFFICULTY] = difficulty.name }
    }

    private companion object {
        val RIDER_MASS = doublePreferencesKey("rider_mass_kg")
        val GEAR_COUNT = intPreferencesKey("gear_count")
        val DIFFICULTY = stringPreferencesKey("difficulty")
    }
}
