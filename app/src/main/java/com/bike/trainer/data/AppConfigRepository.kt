package com.bike.trainer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bike.trainer.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Global, app-wide configuration entered in-app: the MapTiler key for the 3D map
 * plus the Street View motion preferences (Strava credentials are per-rider, see
 * ProfileRepository). Build-time [BuildConfig] values are the fallback.
 */
data class AppConfig(
    val mapTilesKey: String,
    // ---- Street View cached-frame motion (advanced) ----
    /** Default motion style, stored as the [SvMotion] name. */
    val svMotionMode: String = "PARALLAX",
    /** Overall expansion strength (how hard the scene pushes forward). 0..0.5 */
    val svStrength: Float = 0.16f,
    /** Vanishing-point / horizon height as a fraction of the frame. 0.25..0.65 */
    val svHorizon: Float = 0.45f,
    /** Parallax-only: how much faster the near road rushes vs the horizon. 0..3 */
    val svGroundRush: Float = 1.5f,
    // ---- In-ride control button visibility ----
    val showMotionControl: Boolean = true,
    val showViewToggle: Boolean = true,
    val showCaptureButton: Boolean = true,
    /** Workout power tolerance: ± this fraction of target counts as "on target". */
    val workoutTolerance: Float = 0.10f,
    // ---- Learned gear-controller button mapping (protobuf field numbers) ----
    val gearUpField: Int = 1,
    val gearDownField: Int = 2,
    /** Ride scenery: [Scenery.STREET_VIEW], [Scenery.GOOGLE_3D] or [Scenery.STYLIZED]. */
    val scenery: String = Scenery.STREET_VIEW,
    /** 3D World rider design: armour, pro, city or chibi (see res://cyclist.gd). */
    val riderStyle: String = "armour",
    /** 3D World art style: "soft" (low-poly painted, hazy) or "comic" (ink outlines). */
    val worldLook: String = "soft",
    /** 3D World light: morning, afternoon or sunset. */
    val worldTime: String = "afternoon",
    /** 3D World: "real" (the real place, from map data) or a game theme ("robot"). */
    val worldTheme: String = "real",
    /** 3D World: the user's asset pack in use ("" = built-in models). */
    val assetPack: String = "",
    /** Stylized: colour façades / road from the route's prefetched Street View photos. */
    val photoTexture: Boolean = true,
) {
    val mapConfigured: Boolean get() = mapTilesKey.isNotBlank()
}

/** What a ride shows. */
object Scenery {
    const val STREET_VIEW = "STREET_VIEW"
    const val GOOGLE_3D = "GOOGLE_3D"
    const val STYLIZED = "STYLIZED"
    /** The offline cel-shaded 3D world (Godot), built by "Build world". */
    const val WORLD = "WORLD"
}

class AppConfigRepository(private val dataStore: DataStore<Preferences>) {

    val config: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            mapTilesKey = prefs[MAP_TILES_KEY]?.ifBlank { null } ?: BuildConfig.MAPTILES_API_KEY,
            svMotionMode = prefs[SV_MODE] ?: "PARALLAX",
            svStrength = prefs[SV_STRENGTH] ?: 0.16f,
            svHorizon = prefs[SV_HORIZON] ?: 0.45f,
            svGroundRush = prefs[SV_GROUND_RUSH] ?: 1.5f,
            showMotionControl = prefs[SHOW_MOTION] ?: true,
            showViewToggle = prefs[SHOW_VIEW_TOGGLE] ?: true,
            showCaptureButton = prefs[SHOW_CAPTURE] ?: true,
            workoutTolerance = prefs[WORKOUT_TOLERANCE] ?: 0.10f,
            gearUpField = prefs[GEAR_UP_FIELD] ?: 1,
            gearDownField = prefs[GEAR_DOWN_FIELD] ?: 2,
            scenery = prefs[SCENERY]
                ?: if (prefs[STYLIZED_SCENERY] == true) Scenery.STYLIZED else Scenery.STREET_VIEW,
            photoTexture = prefs[PHOTO_TEXTURE] ?: true,
            riderStyle = prefs[RIDER_STYLE] ?: "armour",
            worldLook = prefs[WORLD_LOOK] ?: "soft",
            worldTime = prefs[WORLD_TIME] ?: "afternoon",
            worldTheme = prefs[WORLD_THEME] ?: "real",
            assetPack = prefs[ASSET_PACK] ?: "",
        )
    }

    suspend fun current(): AppConfig = config.first()

    suspend fun setMapTilesKey(key: String) {
        dataStore.edit { it[MAP_TILES_KEY] = key.trim() }
    }

    suspend fun setSvMotionMode(mode: String) {
        dataStore.edit { it[SV_MODE] = mode }
    }

    suspend fun setSvStrength(value: Float) {
        dataStore.edit { it[SV_STRENGTH] = value.coerceIn(0f, 0.5f) }
    }

    suspend fun setSvHorizon(value: Float) {
        dataStore.edit { it[SV_HORIZON] = value.coerceIn(0.25f, 0.65f) }
    }

    suspend fun setSvGroundRush(value: Float) {
        dataStore.edit { it[SV_GROUND_RUSH] = value.coerceIn(0f, 3f) }
    }

    suspend fun setShowMotionControl(show: Boolean) {
        dataStore.edit { it[SHOW_MOTION] = show }
    }

    suspend fun setShowViewToggle(show: Boolean) {
        dataStore.edit { it[SHOW_VIEW_TOGGLE] = show }
    }

    suspend fun setShowCaptureButton(show: Boolean) {
        dataStore.edit { it[SHOW_CAPTURE] = show }
    }

    suspend fun setRiderStyle(style: String) {
        dataStore.edit { it[RIDER_STYLE] = style }
    }

    suspend fun setAssetPack(id: String) {
        dataStore.edit { it[ASSET_PACK] = id }
    }

    suspend fun setWorldTheme(theme: String) {
        dataStore.edit { it[WORLD_THEME] = theme }
    }

    suspend fun setWorldLook(look: String, time: String) {
        dataStore.edit { it[WORLD_LOOK] = look; it[WORLD_TIME] = time }
    }

    suspend fun setPhotoTexture(on: Boolean) {
        dataStore.edit { it[PHOTO_TEXTURE] = on }
    }

    suspend fun setScenery(scenery: String) {
        dataStore.edit { it[SCENERY] = scenery }
    }

    suspend fun setWorkoutTolerance(value: Float) {
        dataStore.edit { it[WORKOUT_TOLERANCE] = value.coerceIn(0.02f, 0.30f) }
    }

    suspend fun setGearButtonMapping(upField: Int, downField: Int) {
        dataStore.edit {
            it[GEAR_UP_FIELD] = upField
            it[GEAR_DOWN_FIELD] = downField
        }
    }

    private companion object {
        val MAP_TILES_KEY = stringPreferencesKey("cfg_map_tiles_key")
        val SV_MODE = stringPreferencesKey("cfg_sv_mode")
        val SV_STRENGTH = floatPreferencesKey("cfg_sv_strength")
        val SV_HORIZON = floatPreferencesKey("cfg_sv_horizon")
        val SV_GROUND_RUSH = floatPreferencesKey("cfg_sv_ground_rush")
        val SHOW_MOTION = booleanPreferencesKey("cfg_show_motion")
        val SHOW_VIEW_TOGGLE = booleanPreferencesKey("cfg_show_view_toggle")
        val SHOW_CAPTURE = booleanPreferencesKey("cfg_show_capture")
        val STYLIZED_SCENERY = booleanPreferencesKey("cfg_stylized_scenery")
        val SCENERY = stringPreferencesKey("cfg_scenery")
        val PHOTO_TEXTURE = booleanPreferencesKey("cfg_photo_texture")
        val RIDER_STYLE = stringPreferencesKey("cfg_rider_style")
        val WORLD_LOOK = stringPreferencesKey("cfg_world_look")
        val WORLD_TIME = stringPreferencesKey("cfg_world_time")
        val WORLD_THEME = stringPreferencesKey("cfg_world_theme")
        val ASSET_PACK = stringPreferencesKey("cfg_asset_pack")
        val WORKOUT_TOLERANCE = floatPreferencesKey("cfg_workout_tolerance")
        val GEAR_UP_FIELD = intPreferencesKey("cfg_gear_up_field")
        val GEAR_DOWN_FIELD = intPreferencesKey("cfg_gear_down_field")
    }
}
