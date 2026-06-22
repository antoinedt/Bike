package com.bike.trainer.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A portable snapshot of the user's settings — rider profiles + stats, in-app
 * keys, and ride preferences — that can be written to a file (e.g. saved to
 * Google Drive via the system picker) and restored after an update/reinstall.
 *
 * Strava OAuth tokens are intentionally NOT included; the user re-connects.
 */
@Serializable
data class BackupBundle(
    val version: Int = 1,
    val profiles: ProfilesState = ProfilesState(),
    val stravaClientId: String = "",
    val stravaClientSecret: String = "",
    val mapTilesKey: String = "",
    val gearCount: Int = 12,
    val difficulty: String = "ROLLING",
)

class BackupManager(
    private val profiles: ProfileRepository,
    private val config: AppConfigRepository,
    private val settings: SettingsRepository,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Serialize the current settings to a JSON string. */
    suspend fun export(): String {
        val cfg = config.current()
        val s = settings.settings.first()
        val bundle = BackupBundle(
            profiles = profiles.current(),
            stravaClientId = cfg.stravaClientId,
            stravaClientSecret = cfg.stravaClientSecret,
            mapTilesKey = cfg.mapTilesKey,
            gearCount = s.gearCount,
            difficulty = s.difficulty.name,
        )
        return json.encodeToString(bundle)
    }

    /** Restore settings from a backup JSON string. Returns true on success. */
    suspend fun import(raw: String): Boolean {
        val bundle = runCatching { json.decodeFromString<BackupBundle>(raw) }.getOrNull() ?: return false
        profiles.replaceAll(bundle.profiles)
        config.setStravaCredentials(bundle.stravaClientId, bundle.stravaClientSecret)
        config.setMapTilesKey(bundle.mapTilesKey)
        settings.setGearCount(bundle.gearCount)
        runCatching { com.bike.trainer.route.RouteGenerator.Difficulty.valueOf(bundle.difficulty) }
            .getOrNull()?.let { settings.setDifficulty(it) }
        return true
    }
}
