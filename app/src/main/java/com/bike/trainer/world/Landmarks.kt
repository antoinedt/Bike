package com.bike.trainer.world

import kotlin.math.abs

/**
 * Landmarks: buildings / building parts fetched with their full OSM tags (domes,
 * spires, onion roofs, churches …), merged over the vector-tile buildings, which
 * only carry heights.
 *
 * - A landmark that matches a tile building (centroid inside, similar size and
 *   base height) replaces it, so its roof shape and type are used.
 * - An unmatched building part (e.g. a dome sitting on a base) is added.
 * - An unmatched outline (the tiles show its parts instead) lends its type to the
 *   tile parts inside it, so they are drawn as e.g. a church.
 */
object Landmarks {

    fun merge(osm: OsmData, landmarks: List<OsmArea>): OsmData {
        if (landmarks.isEmpty()) return osm
        val replaced = HashSet<OsmArea>()
        val adopted = HashMap<OsmArea, Map<String, String>>()
        val added = ArrayList<OsmArea>()
        val tileBuildings = osm.areas.filter { it.tags.containsKey("building") || it.tags.containsKey("building:part") }
        val info = tileBuildings.associateWith { Shape.of(it) }

        for (lm in landmarks) {
            val s = Shape.of(lm) ?: continue
            val isPart = lm.tags.containsKey("building:part") && !lm.tags.containsKey("building")
            var matched = false
            val inside = ArrayList<OsmArea>()
            for (tb in tileBuildings) {
                val t = info[tb] ?: continue
                if (t.cx < s.minX || t.cx > s.maxX || t.cy < s.minY || t.cy > s.maxY) continue
                if (!Geometry.contains(s.ring, t.cx, t.cy)) continue
                inside.add(tb)
                val ratio = t.area / s.area
                if (ratio in 0.45..2.2 && abs(t.base - s.base) < 2.5) {
                    replaced.add(tb)
                    matched = true
                }
            }
            when {
                matched || isPart -> added.add(lm)
                else -> {
                    // Keep the tile parts, give them the landmark's identity.
                    val keep = lm.tags.filterKeys { it == "building" || it == "amenity" || it == "religion" || it == "building:colour" || it == "building:material" }
                    for (tb in inside) if (tb !in replaced) adopted[tb] = keep
                }
            }
        }
        val areas = ArrayList<OsmArea>(osm.areas.size + added.size)
        for (a in osm.areas) {
            if (a in replaced) continue
            val extra = adopted[a]
            areas.add(if (extra == null) a else OsmArea(a.id, a.tags + extra, a.outers, a.inners))
        }
        // A landmark may come twice (two route sections): keep one.
        val seen = HashSet<Long>()
        for (a in added) if (seen.add(a.id)) areas.add(a)
        return OsmData(osm.points, osm.ways, areas)
    }

    private class Shape(val ring: DoubleArray, val area: Double, val cx: Double, val cy: Double, val base: Double,
                        val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        companion object {
            fun of(a: OsmArea): Shape? {
                val r = a.outers.maxByOrNull { abs(Geometry.area(it)) } ?: return null
                val n = Geometry.count(r)
                if (n < 3) return null
                val area = abs(Geometry.area(r, n))
                if (area < 1.0) return null
                val c = Geometry.centroid(r, n)
                val b = Geometry.bbox(listOf(r))
                val base = parseMeters(a.tags["min_height"]) ?: a.tags["building:min_level"]?.toDoubleOrNull()?.let { it * 3.0 } ?: 0.0
                return Shape(r, area, c[0], c[1], base, b[0], b[1], b[2], b[3])
            }
        }
    }
}
