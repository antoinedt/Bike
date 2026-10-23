package com.bike.trainer.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.cos

/**
 * Maps latitude / longitude to the route's local metres (x = east, y = north from
 * its first point) — the same equirectangular projection GpxImporter uses.
 */
class Geo(private val lat0: Double, private val lon0: Double) {
    private val mLat = EARTH_RADIUS * PI / 180.0
    private val mLon = mLat * cos(Math.toRadians(lat0))
    fun x(lon: Double) = (lon - lon0) * mLon
    fun y(lat: Double) = (lat - lat0) * mLat
    fun lat(y: Double) = lat0 + y / mLat
    fun lon(x: Double) = lon0 + x / mLon

    private companion object {
        const val EARTH_RADIUS = 6_371_000.0
    }
}

/** An OSM node with tags (tree, lamp, bench …) in local metres. */
class OsmPoint(val id: Long, val x: Double, val y: Double, val tags: Map<String, String>)

/** An OSM way (line or closed ring) in local metres; xy interleaved. */
class OsmWay(val id: Long, val tags: Map<String, String>, val xy: DoubleArray) {
    val closed: Boolean get() = xy.size >= 8 && xy[0] == xy[xy.size - 2] && xy[1] == xy[xy.size - 1]
}

/** A polygon area (closed way or multipolygon relation): outer + inner rings. */
class OsmArea(val id: Long, val tags: Map<String, String>, val outers: List<DoubleArray>, val inners: List<DoubleArray>) {
    val rings: List<DoubleArray> get() = outers + inners
}

/**
 * Raw OpenStreetMap data for a route corridor, as returned by the Overpass API
 * (`[out:json]` … `out geom;`): every node with tags, every way with its inline
 * geometry, and multipolygon relations with member geometry.
 */
class OsmData(val points: List<OsmPoint>, val ways: List<OsmWay>, val areas: List<OsmArea>) {

    companion object {
        /** Tags that make a closed way an area rather than a line. */
        private val AREA_KEYS = setOf(
            "building", "building:part", "landuse", "natural", "leisure", "amenity", "water",
            "landcover", "place", "parking", "area:highway", "man_made",
        )

        fun parse(json: String, geo: Geo): OsmData = merge(listOf(json), geo)

        /** Parse several Overpass responses (e.g. one per route section), de-duplicated. */
        fun merge(jsons: List<String>, geo: Geo): OsmData {
            val points = LinkedHashMap<Long, OsmPoint>()
            val ways = LinkedHashMap<Long, OsmWay>()
            val areas = LinkedHashMap<Long, OsmArea>()
            val parser = Json { ignoreUnknownKeys = true }
            for (text in jsons) {
                val root = parser.parseToJsonElement(text).jsonObject
                val elements = root["elements"]?.jsonArray ?: continue
                for (el in elements) {
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.longOrNull ?: continue
                    val tags = tags(o)
                    when (o["type"]?.jsonPrimitive?.content) {
                        "node" -> if (tags.isNotEmpty()) {
                            val lat = o["lat"]?.jsonPrimitive?.double ?: continue
                            val lon = o["lon"]?.jsonPrimitive?.double ?: continue
                            points[id] = OsmPoint(id, geo.x(lon), geo.y(lat), tags)
                        }
                        "way" -> {
                            if (tags.isEmpty()) continue
                            val xy = geometry(o["geometry"] as? JsonArray, geo) ?: continue
                            val way = OsmWay(id, tags, xy)
                            val isArea = way.closed && tags["area"] != "no" &&
                                (tags.keys.any { it in AREA_KEYS } || tags["area"] == "yes")
                            if (isArea) areas[id] = OsmArea(id, tags, listOf(xy), emptyList())
                            if (!isArea || tags.containsKey("highway") || tags.containsKey("barrier")) ways[id] = way
                        }
                        "relation" -> {
                            if (tags["type"] != "multipolygon" && tags["type"] != "building") continue
                            val outers = ArrayList<DoubleArray>()
                            val inners = ArrayList<DoubleArray>()
                            val members = o["members"]?.jsonArray ?: continue
                            val outerParts = ArrayList<DoubleArray>()
                            val innerParts = ArrayList<DoubleArray>()
                            for (m in members) {
                                val mo = m.jsonObject
                                if (mo["type"]?.jsonPrimitive?.content != "way") continue
                                val xy = geometry(mo["geometry"] as? JsonArray, geo) ?: continue
                                if (mo["role"]?.jsonPrimitive?.content == "inner") innerParts.add(xy) else outerParts.add(xy)
                            }
                            outers.addAll(joinRings(outerParts))
                            inners.addAll(joinRings(innerParts))
                            if (outers.isNotEmpty()) areas[-id] = OsmArea(-id, tags, outers, inners)
                        }
                    }
                }
            }
            return OsmData(points.values.toList(), ways.values.toList(), areas.values.toList())
        }

        private fun tags(o: JsonObject): Map<String, String> =
            (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

        private fun geometry(arr: JsonArray?, geo: Geo): DoubleArray? {
            if (arr == null || arr.size < 2) return null
            val out = DoubleArray(arr.size * 2)
            var i = 0
            for (p in arr) {
                val po = p as? JsonObject ?: return null
                val lat = po["lat"]?.jsonPrimitive?.double ?: return null
                val lon = po["lon"]?.jsonPrimitive?.double ?: return null
                out[i++] = geo.x(lon); out[i++] = geo.y(lat)
            }
            return out
        }

        /** Stitch multipolygon member ways into closed rings (members can be split). */
        private fun joinRings(parts: List<DoubleArray>): List<DoubleArray> {
            val open = parts.toMutableList()
            val rings = ArrayList<DoubleArray>()
            fun same(a: DoubleArray, ai: Int, b: DoubleArray, bi: Int) =
                kotlin.math.abs(a[ai] - b[bi]) < 0.01 && kotlin.math.abs(a[ai + 1] - b[bi + 1]) < 0.01
            while (open.isNotEmpty()) {
                var cur = open.removeAt(0)
                var guard = 0
                while (!same(cur, 0, cur, cur.size - 2) && guard++ < 1000) {
                    val idx = open.indexOfFirst { p ->
                        same(cur, cur.size - 2, p, 0) || same(cur, cur.size - 2, p, p.size - 2)
                    }
                    if (idx < 0) break
                    var p = open.removeAt(idx)
                    if (!same(cur, cur.size - 2, p, 0)) p = reversePts(p)
                    cur = cur + p.copyOfRange(2, p.size)
                }
                if (cur.size >= 8) rings.add(cur)
            }
            return rings
        }

        private fun reversePts(p: DoubleArray): DoubleArray {
            val n = p.size / 2
            return DoubleArray(p.size) { i -> val k = n - 1 - i / 2; p[2 * k + (i and 1)] }
        }
    }
}

/** Parse an OSM length tag ("12", "12 m", "40'") into metres, or null. */
internal fun parseMeters(s: String?): Double? {
    if (s == null) return null
    val t = s.trim().lowercase().replace(",", ".")
    if (t.endsWith("'")) return t.removeSuffix("'").toDoubleOrNull()?.times(0.3048)
    return t.removeSuffix("m").trim().split(" ", ";").firstOrNull()?.toDoubleOrNull()
}

/** OSM `colour` tag ("#aabbcc", "#abc", or a CSS colour name) to RGB, or null. */
internal fun parseColour(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().lowercase()
    if (t.startsWith("#")) {
        val h = t.substring(1)
        return when (h.length) {
            6 -> h.toIntOrNull(16)
            3 -> h.toIntOrNull(16)?.let { v ->
                val r = (v shr 8) and 0xF; val g = (v shr 4) and 0xF; val b = v and 0xF
                (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            else -> null
        }
    }
    return CSS_COLOURS[t]
}

private val CSS_COLOURS = mapOf(
    "white" to 0xF2F0EA, "black" to 0x2A2A2A, "grey" to 0x9A9A9A, "gray" to 0x9A9A9A,
    "lightgrey" to 0xC8C8C8, "lightgray" to 0xC8C8C8, "darkgrey" to 0x5A5A5A, "darkgray" to 0x5A5A5A,
    "silver" to 0xC0C0C0, "red" to 0xB5473A, "darkred" to 0x7A2A22, "brown" to 0x8A5A3C,
    "maroon" to 0x7A2E2A, "orange" to 0xE08A3C, "yellow" to 0xE8D27A, "beige" to 0xE6D8B8,
    "tan" to 0xD2B48C, "khaki" to 0xD8CC8C, "green" to 0x6E9A5A, "darkgreen" to 0x3E6A3A,
    "olive" to 0x8A8A4A, "blue" to 0x4A6EA8, "lightblue" to 0x9CC4E0, "navy" to 0x2A3A6A,
    "pink" to 0xE8A8B0, "salmon" to 0xE8967A, "cream" to 0xF0E6C8, "ivory" to 0xF4F0E0,
    "sandybrown" to 0xE0A060, "peru" to 0xC08040, "sienna" to 0xA0522D, "terracotta" to 0xC0603C,
)
