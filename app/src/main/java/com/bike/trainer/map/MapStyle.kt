package com.bike.trainer.map

import com.bike.trainer.BuildConfig

/**
 * Produces the MapLibre style used by the 3D ride view.
 *
 * When a MapTiler key is configured at build time we hand-build a style JSON
 * that turns on the things that make the scene feel real and three-dimensional:
 *  - satellite imagery draped over
 *  - 3D **terrain** + hillshade from a raster-DEM source
 *  - extruded **OpenStreetMap buildings** (OpenMapTiles "building" layer)
 *
 * Without a key we fall back to MapLibre's free demo style URL — still a real
 * world map (so GPX routes show the right place) but flat, no terrain/buildings.
 */
object MapStyle {

    val hasMapTilerKey: Boolean get() = BuildConfig.MAPTILES_API_KEY.isNotBlank()

    /** Demo style works with no key but has no terrain/buildings/satellite. */
    const val DEMO_STYLE_URL = "https://demotiles.maplibre.org/style.json"

    /** Terrain exaggeration; >1 makes climbs read more dramatically. */
    private const val TERRAIN_EXAGGERATION = 1.3

    fun styleJson(): String {
        val key = BuildConfig.MAPTILES_API_KEY
        val satellite = "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$key"
        val dem = "https://api.maptiler.com/tiles/terrain-rgb-v2/{z}/{x}/{y}.webp?key=$key"
        val vectorTiles = "https://api.maptiler.com/tiles/v3/tiles.json?key=$key"

        return """
        {
          "version": 8,
          "name": "Bike3D",
          "sources": {
            "satellite": {
              "type": "raster",
              "tiles": ["$satellite"],
              "tileSize": 256,
              "maxzoom": 20
            },
            "dem": {
              "type": "raster-dem",
              "tiles": ["$dem"],
              "tileSize": 256,
              "encoding": "mapbox",
              "maxzoom": 12
            },
            "omt": {
              "type": "vector",
              "url": "$vectorTiles"
            }
          },
          "terrain": { "source": "dem", "exaggeration": $TERRAIN_EXAGGERATION },
          "light": { "anchor": "viewport", "position": [1.5, 90, 80], "intensity": 0.4 },
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#10202E" } },
            { "id": "satellite", "type": "raster", "source": "satellite" },
            {
              "id": "hillshade", "type": "hillshade", "source": "dem",
              "paint": { "hillshade-exaggeration": 0.45, "hillshade-shadow-color": "#000000" }
            },
            {
              "id": "buildings", "type": "fill-extrusion", "source": "omt",
              "source-layer": "building", "minzoom": 13,
              "paint": {
                "fill-extrusion-color": "#C8CDD2",
                "fill-extrusion-height": ["get", "render_height"],
                "fill-extrusion-base": ["get", "render_min_height"],
                "fill-extrusion-opacity": 0.85
              }
            }
          ]
        }
        """.trimIndent()
    }
}
