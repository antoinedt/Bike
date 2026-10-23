package com.bike.trainer.world

import com.bike.trainer.route.Mvt
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Turns OpenMapTiles vector-tile features (OpenFreeMap, z14) into the same OSM-style
 * data the scene builder reads from Overpass: buildings with heights and colours,
 * streets / paths / rail by type, land use, land cover, water. Tag values are mapped
 * back to their OpenStreetMap equivalents so the builder needs no special cases.
 */
object TileOsm {
    /** Layers worth decoding for the world. */
    val LAYERS = setOf("building", "transportation", "landuse", "landcover", "water", "waterway")

    private var nextId = 1L shl 40

    fun convert(features: List<Mvt.Feature>, tx: Int, ty: Int, zoom: Int, geo: Geo): OsmData {
        val ways = ArrayList<OsmWay>()
        val areas = ArrayList<OsmArea>()
        val n = (1 shl zoom).toDouble()
        for (f in features) {
            val tags = tags(f) ?: continue
            val ext = f.extent.toDouble()
            fun local(part: FloatArray): DoubleArray {
                val out = DoubleArray(part.size)
                var i = 0
                while (i < part.size) {
                    val lon = (tx + part[i] / ext) / n * 360.0 - 180.0
                    val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * (ty + part[i + 1] / ext) / n))))
                    out[i] = geo.x(lon); out[i + 1] = geo.y(lat)
                    i += 2
                }
                return out
            }
            when (f.type) {
                Mvt.LINE -> f.parts.forEach { p ->
                    for (piece in clipLine(p, ext.toFloat())) if (piece.size >= 4) ways.add(OsmWay(nextId++, tags, local(piece)))
                }
                Mvt.POLYGON -> {
                    // MVT: exterior rings have positive area in tile space, holes negative.
                    var outers = ArrayList<DoubleArray>()
                    var inners = ArrayList<DoubleArray>()
                    fun flush() {
                        if (outers.isNotEmpty()) areas.add(OsmArea(nextId++, tags, outers, inners))
                        outers = ArrayList(); inners = ArrayList()
                    }
                    for (raw in f.parts) {
                        if (raw.size < 6) continue
                        val outer = tileArea(raw) > 0
                        // Clip to the tile's own square: tiles overlap by a margin, and a
                        // feature drawn twice there would flicker against itself.
                        val p = clipRing(raw, ext.toFloat())
                        if (p.size < 6) continue
                        val closed = closeRing(local(p))
                        if (outer) { flush(); outers.add(closed) } else inners.add(closed)
                    }
                    flush()
                }
            }
        }
        return OsmData(emptyList(), ways, areas)
    }

    /** Sutherland–Hodgman clip of a ring to the square 0..[ext] (keeps orientation). */
    private fun clipRing(ring: FloatArray, ext: Float): FloatArray {
        var pts = ring
        // Drop a repeated closing point.
        if (pts.size >= 4 && pts[0] == pts[pts.size - 2] && pts[1] == pts[pts.size - 1]) pts = pts.copyOf(pts.size - 2)
        for (edge in 0..3) {
            val n = pts.size / 2
            if (n == 0) break
            val out = ArrayList<Float>(pts.size + 8)
            fun inside(x: Float, y: Float) = when (edge) { 0 -> x >= 0f; 1 -> x <= ext; 2 -> y >= 0f; else -> y <= ext }
            fun cut(ax: Float, ay: Float, bx: Float, by: Float): FloatArray {
                val t = when (edge) { 0 -> (0f - ax) / (bx - ax); 1 -> (ext - ax) / (bx - ax); 2 -> (0f - ay) / (by - ay); else -> (ext - ay) / (by - ay) }
                return floatArrayOf(ax + (bx - ax) * t, ay + (by - ay) * t)
            }
            for (i in 0 until n) {
                val ax = pts[2 * ((i + n - 1) % n)]; val ay = pts[2 * ((i + n - 1) % n) + 1]
                val bx = pts[2 * i]; val by = pts[2 * i + 1]
                val ain = inside(ax, ay); val bin = inside(bx, by)
                if (bin) {
                    if (!ain) cut(ax, ay, bx, by).let { out.add(it[0]); out.add(it[1]) }
                    out.add(bx); out.add(by)
                } else if (ain) cut(ax, ay, bx, by).let { out.add(it[0]); out.add(it[1]) }
            }
            pts = out.toFloatArray()
        }
        return pts
    }

    /** Clip a polyline to the square 0..[ext]; it may split into several pieces. */
    private fun clipLine(line: FloatArray, ext: Float): List<FloatArray> {
        val pieces = ArrayList<FloatArray>()
        var cur = ArrayList<Float>()
        fun flush() { if (cur.size >= 4) pieces.add(cur.toFloatArray()); cur = ArrayList() }
        val n = line.size / 2
        for (i in 0 until n - 1) {
            var x0 = line[2 * i]; var y0 = line[2 * i + 1]; var x1 = line[2 * i + 2]; var y1 = line[2 * i + 3]
            // Liang–Barsky.
            var t0 = 0f; var t1 = 1f
            val dx = x1 - x0; val dy = y1 - y0
            var visible = true
            for ((pp, qq) in listOf(-dx to x0, dx to ext - x0, -dy to y0, dy to ext - y0)) {
                if (pp == 0f) { if (qq < 0f) { visible = false; break } }
                else {
                    val r = qq / pp
                    if (pp < 0f) { if (r > t1) { visible = false; break }; if (r > t0) t0 = r }
                    else { if (r < t0) { visible = false; break }; if (r < t1) t1 = r }
                }
            }
            if (!visible) { flush(); continue }
            val ax = x0 + dx * t0; val ay = y0 + dy * t0
            val bx = x0 + dx * t1; val by = y0 + dy * t1
            if (cur.isEmpty() || cur[cur.size - 2] != ax || cur[cur.size - 1] != ay) { flush(); cur.add(ax); cur.add(ay) }
            cur.add(bx); cur.add(by)
            if (t1 < 1f) flush()
        }
        flush()
        return pieces
    }

    private fun tileArea(p: FloatArray): Double {
        var s = 0.0
        val k = p.size / 2
        for (i in 0 until k) {
            val j = (i + 1) % k
            s += p[2 * i].toDouble() * p[2 * j + 1] - p[2 * j].toDouble() * p[2 * i + 1]
        }
        return s
    }

    /** OsmData expects closed rings (first point repeated at the end). */
    private fun closeRing(r: DoubleArray): DoubleArray =
        if (r[0] == r[r.size - 2] && r[1] == r[r.size - 1]) r else r + doubleArrayOf(r[0], r[1])

    /** OpenMapTiles attributes -> equivalent OSM tags (null = not used by the world). */
    private fun tags(f: Mvt.Feature): Map<String, String>? {
        val cls = f.str("class") ?: ""
        val sub = f.str("subclass") ?: ""
        return when (f.layer) {
            "building" -> {
                if (f.props["hide_3d"] == true) return null
                buildMap {
                    put("building", "yes")
                    f.num("render_height")?.let { put("height", it.toString()) }
                    f.num("render_min_height")?.let { if (it > 0) put("min_height", it.toString()) }
                    f.str("colour")?.let { put("building:colour", it) }
                }
            }
            "transportation" -> {
                val t = HashMap<String, String>()
                when (cls) {
                    "motorway", "trunk", "primary", "secondary", "tertiary" ->
                        t["highway"] = if ((f.num("ramp") ?: 0.0) > 0) "${cls}_link" else cls
                    "minor" -> t["highway"] = "residential"
                    "service" -> t["highway"] = "service"
                    "track" -> t["highway"] = "track"
                    "busway" -> t["highway"] = "busway"
                    "path" -> t["highway"] = if (sub in setOf("footway", "cycleway", "steps", "pedestrian", "bridleway", "path")) sub else "path"
                    "rail", "transit" -> t["railway"] = when (sub) {
                        "tram" -> "tram"; "light_rail" -> "light_rail"; "subway" -> "subway"; "narrow_gauge" -> "narrow_gauge"
                        else -> "rail"
                    }
                    else -> return null
                }
                if (f.str("brunnel") == "tunnel") t["tunnel"] = "yes"
                if (f.str("brunnel") == "bridge") t["bridge"] = "yes"
                if ((f.num("oneway") ?: 0.0) > 0) t["oneway"] = "yes"
                if (f.str("surface") == "unpaved") t["surface"] = "unpaved"
                if (f.type == Mvt.POLYGON) t["area"] = "yes"
                t
            }
            "landuse" -> when (cls) {
                "pitch", "playground", "stadium", "track", "theme_park", "zoo" -> mapOf("leisure" to cls)
                "" -> null
                else -> mapOf("landuse" to cls)
            }
            "landcover" -> when (cls) {
                "wood" -> if (sub == "forest") mapOf("landuse" to "forest") else mapOf("natural" to "wood")
                "grass" -> if (sub in setOf("park", "garden", "recreation_ground")) mapOf("leisure" to (if (sub == "recreation_ground") "park" else sub))
                else mapOf("landuse" to "grass")
                "farmland" -> mapOf("landuse" to "farmland")
                "wetland" -> mapOf("natural" to "wetland")
                "sand" -> mapOf("natural" to "sand")
                "rock" -> mapOf("natural" to "bare_rock")
                "ice" -> mapOf("natural" to "glacier")
                else -> null
            }
            "water" -> if (f.str("brunnel") == "tunnel") null else mapOf("natural" to "water")
            "waterway" -> if (f.str("brunnel") == "tunnel") null else mapOf("waterway" to cls.ifEmpty { "stream" })
            else -> null
        }
    }
}

/** Combine several sources (map tiles + Overpass points) into one data set. */
fun OsmData.plus(other: OsmData) = OsmData(points + other.points, ways + other.ways, areas + other.areas)
