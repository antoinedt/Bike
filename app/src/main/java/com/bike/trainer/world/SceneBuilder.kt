package com.bike.trainer.world

import com.bike.trainer.route.Route
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Ground height (metres) at a local position (x = east, y = north). */
fun interface HeightSource {
    fun height(x: Double, y: Double): Double
}

/**
 * Builds the offline 3D world of a route ("Build world"): from the route, raw
 * OpenStreetMap data of its corridor and terrain heights, it writes
 *  - `chunk_NNN.glb`: painted ground tiles (land use, every road at its real width,
 *    pavements, cycle lanes, markings, water) and buildings (real footprints and
 *    heights, gabled / hipped roofs where tagged or obvious, real or palette colours,
 *    UVs the façade shader turns into windows and shopfronts);
 *  - `chunk_NNN.json`: ink-line edges and props (mapped trees, lamps, benches, bus
 *    stops, signals …, plus trees scattered in woods / parks);
 *  - `world.json`: the route in scene space and the chunk list.
 * Scene space is Godot's: X = east, Y = up, Z = -north (metres from the route start).
 * Pure Kotlin: runs on the phone and on a desktop JVM alike.
 */
class SceneBuilder(
    private val route: Route,
    private val osm: OsmData,
    dem: HeightSource,
) {
    /**
     * Everything stands on the rendered ground: the DEM sampled on the ground
     * mesh's 8 m grid and interpolated over the very same triangles, so streets,
     * buildings and props never sink into (or float over) the terrain.
     */
    private val terrain = object : HeightSource {
        private val grid = HashMap<Long, Double>()
        private fun g(i: Long, j: Long): Double =
            grid.getOrPut((i shl 32) or (j and 0xFFFFFFFFL)) { dem.height(i * GRID, j * GRID) }

        override fun height(x: Double, y: Double): Double {
            val fx = x / GRID; val fy = y / GRID
            val i = floor(fx).toLong(); val j = floor(fy).toLong()
            val u = fx - i; val v = fy - j
            val ha = g(i, j); val hb = g(i + 1, j); val hc = g(i, j + 1); val hd = g(i + 1, j + 1)
            // Same split as groundMesh(): triangles (a, c, b) and (b, c, d).
            return if (u + v <= 1.0) ha + u * (hb - ha) + v * (hc - ha)
            else hd + (1 - u) * (hc - hd) + (1 - v) * (hb - hd)
        }
    }

    companion object {
        const val VERSION = 1
        const val CHUNK_LEN = 400.0
        private const val TILE = 160.0
        private const val TEX = 512
        private const val GRID = 8.0
        private const val GROUND_RANGE = 280.0
        private const val BUILDING_RANGE = 230.0
        private const val PROP_RANGE = 160.0
        private const val BAY = 3.2
        private const val FLOOR = 3.2

        // Illustration palette (flat, warm, a little saturated).
        private const val C_MEADOW = 0xB9C46E
        private const val C_GRASS = 0x8DBE5A
        private const val C_PARK = 0x82B655
        private const val C_FOREST = 0x4F8A3E
        private const val C_SCRUB = 0x8FA35A
        private const val C_FARM = 0xDCC66A
        private const val C_RESIDENTIAL = 0xD2CAB5
        private const val C_COMMERCIAL = 0xD6C7B2
        private const val C_INDUSTRIAL = 0xBFB9AE
        private const val C_WATER = 0x4D9AD6
        private const val C_WETLAND = 0x7FB08C
        private const val C_SAND = 0xEBD9A0
        private const val C_ROCK = 0xA8A39B
        private const val C_PARKING = 0xAAAAA6
        private const val C_PAVING = 0xD9D2C2
        private const val C_SIDEWALK = 0xD3CEC4
        private const val C_CURB = 0x9B978E
        private const val C_ASPHALT = 0x7A7F8A
        private const val C_MARKING = 0xF3F2EC
        private const val C_CYCLE = 0xC0564A
        private const val C_PATH = 0xDACDB1
        private const val C_TRACK = 0xB39466
        private const val C_RAIL_BED = 0x8E8478
        private const val C_RAIL = 0x4A4642
        private const val C_SAND_PITCH = 0xD9B98A

        private val WALLS = intArrayOf(0xEADBB0, 0xE3C489, 0xE7A983, 0xD6D3CB, 0xF1ECE2, 0xBFD8C4, 0xE9CFA0, 0xC9B9A6, 0xB86A4F, 0xF0DFC6)
        private val ROOF_FLAT = intArrayOf(0x8F8A84, 0x9C968E, 0x7E7A76)
        private val ROOF_PITCHED = intArrayOf(0xB65A3E, 0x4E5560, 0xA4523A, 0x5B5F68)
    }

    /** What a chunk file holds (also listed in world.json). */
    class ChunkInfo(val index: Int, val d0: Double, val d1: Double)

    private class Chunk {
        val ground = ArrayList<Pair<String, Pair<MeshData, ByteArray>>>()
        val walls = MeshData()
        val roofs = MeshData()
        val roads = MeshData()
        val edges = FloatArr()
        val props = StringBuilder()
        var propCount = 0
    }

    // Route samples every 5 m (for nearest-route queries and the manifest).
    private val rs = ArrayList<DoubleArray>() // [d, x, y]
    private val rBuckets = HashMap<Long, MutableList<Int>>()
    private val chunks = HashMap<Int, Chunk>()

    init {
        var d = 0.0
        while (true) {
            val p = route.pointAt(d)
            rs.add(doubleArrayOf(d, p.x, p.y))
            if (d >= route.totalDistance) break
            d = min(d + 5.0, route.totalDistance)
        }
        rs.forEachIndexed { i, s -> rBuckets.getOrPut(key(s[1], s[2], 50.0)) { ArrayList() }.add(i) }
    }

    private fun key(x: Double, y: Double, cell: Double): Long =
        (floor(x / cell).toLong() shl 32) or (floor(y / cell).toLong() and 0xFFFFFFFFL)

    /** (distance along route, distance from route) of the nearest route sample. */
    private fun nearest(x: Double, y: Double, maxRange: Double): DoubleArray? {
        val reach = (maxRange / 50.0).toInt() + 1
        val bx = floor(x / 50.0).toLong(); val by = floor(y / 50.0).toLong()
        var best = Double.MAX_VALUE; var bestD = 0.0
        for (i in bx - reach..bx + reach) for (j in by - reach..by + reach) {
            rBuckets[(i shl 32) or (j and 0xFFFFFFFFL)]?.forEach { k ->
                val s = rs[k]
                val dd = hypot(s[1] - x, s[2] - y)
                if (dd < best) { best = dd; bestD = s[0] }
            }
        }
        return if (best <= maxRange) doubleArrayOf(bestD, best) else null
    }

    private fun chunkFor(d: Double): Chunk = chunks.getOrPut(floor(d / CHUNK_LEN).toInt()) { Chunk() }

    // ---------------------------------------------------------------------------------
    // Entry point

    /** Build everything; [write] receives each output file (name, bytes). */
    fun build(write: (String, ByteArray) -> Unit, progress: (String) -> Unit = {}) {
        progress("Ground")
        buildGround()
        progress("Streets")
        buildLinework()
        progress("Buildings")
        buildBuildings()
        progress("Props")
        buildProps()
        progress("Writing")
        val infos = ArrayList<ChunkInfo>()
        for ((index, c) in chunks.toSortedMap()) {
            val glb = GlbWriter()
            c.ground.forEach { (name, g) -> glb.add(name, g.first, "ground", g.second) }
            glb.add("walls", c.walls, "walls")
            glb.add("roofs", c.roofs, "roofs")
            glb.add("roads", c.roads, "roads")
            val name = "chunk_%03d".format(index)
            if (!glb.isEmpty()) write("$name.glb", glb.bytes())
            val edges = StringBuilder()
            for (i in 0 until c.edges.size) {
                if (i > 0) edges.append(',')
                edges.append(fmt(c.edges.a[i].toDouble()))
            }
            write("$name.json", "{\"edges\":[$edges],\"props\":[${c.props}]}".toByteArray())
            infos.add(ChunkInfo(index, index * CHUNK_LEN, (index + 1) * CHUNK_LEN))
        }
        write("world.json", manifest(infos).toByteArray())
    }

    private fun manifest(infos: List<ChunkInfo>): String {
        val sb = StringBuilder("{\"version\":$VERSION,\"name\":\"${route.name.replace("\"", "'")}\",")
        sb.append("\"length\":${fmt(route.totalDistance)},\"chunkLength\":${fmt(CHUNK_LEN)},")
        sb.append("\"route\":[")
        rs.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append(fmt(s[1])).append(',').append(fmt(terrain.height(s[1], s[2]))).append(',').append(fmt(-s[2]))
        }
        sb.append("],\"routeStep\":5,\"chunks\":[")
        infos.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"i\":${c.index},\"d0\":${fmt(c.d0)},\"d1\":${fmt(c.d1)}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }

    // ---------------------------------------------------------------------------------
    // Ground: painted tiles draped on the terrain

    private class Feature(val bbox: DoubleArray, val draw: (Raster) -> Unit, val order: Int)

    private fun buildGround() {
        val features = groundFeatures().sortedBy { it.order }
        // Tiles touching the corridor.
        val tiles = HashSet<Long>()
        for (s in rs) {
            val r = (GROUND_RANGE / TILE).toInt() + 1
            val tx = floor(s[1] / TILE).toLong(); val ty = floor(s[2] / TILE).toLong()
            for (i in tx - r..tx + r) for (j in ty - r..ty + r) tiles.add((i shl 32) or (j and 0xFFFFFFFFL))
        }
        for (t in tiles) {
            val ix = (t shr 32).toInt(); val iy = t.toInt()
            val ox = ix * TILE; val oy = iy * TILE
            val near = nearest(ox + TILE / 2, oy + TILE / 2, GROUND_RANGE + TILE * 0.71) ?: continue
            val raster = Raster(TEX, ox, oy, TILE)
            raster.fill(C_MEADOW)
            for (f in features) {
                val b = f.bbox
                if (b[2] < ox || b[0] > ox + TILE || b[3] < oy || b[1] > oy + TILE) continue
                f.draw(raster)
            }
            val mesh = groundMesh(ox, oy)
            chunkFor(near[0]).ground.add("ground_${ix}_$iy" to (mesh to raster.png()))
        }
    }

    private fun groundMesh(ox: Double, oy: Double): MeshData {
        val m = MeshData()
        val n = (TILE / GRID).toInt()
        val h = Array(n + 1) { j -> DoubleArray(n + 1) { i -> terrain.height(ox + i * GRID, oy + j * GRID) } }
        for (j in 0..n) for (i in 0..n) {
            val hx = h[j][min(n, i + 1)] - h[j][max(0, i - 1)]
            val hy = h[min(n, j + 1)][i] - h[max(0, j - 1)][i]
            // Normal in Godot space (z = -north).
            var nx = -hx / (2 * GRID); var ny = 1.0; var nz = hy / (2 * GRID)
            val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
            val x = ox + i * GRID; val y = oy + j * GRID
            m.v(x, h[j][i] - 0.05, -y, nx, ny, nz, 0xFFFFFF, i.toDouble() / n, 1.0 - j.toDouble() / n)
        }
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * (n + 1) + i; val b = a + 1; val c = a + n + 1; val d = c + 1
            m.tri(a, c, b); m.tri(b, c, d)
        }
        return m
    }

    private fun groundFeatures(): List<Feature> {
        val out = ArrayList<Feature>()
        for (a in osm.areas) {
            val t = a.tags
            if (t.containsKey("building") || t.containsKey("building:part")) continue
            val c = areaColour(t) ?: continue
            out.add(Feature(Geometry.bbox(a.outers), { r -> r.polygon(a.rings, c) }, areaOrder(t)))
        }
        return out
    }

    // ---------------------------------------------------------------------------------
    // Streets: real geometry ribbons on the terrain (crisp at any distance)

    private object Lift {
        const val WATER = 0.02; const val SIDEWALK = 0.05; const val PATH = 0.07
        const val ASPHALT = 0.09; const val LANE = 0.11; const val MARK = 0.13
    }

    private fun buildLinework() {
        for (w in osm.ways) {
            val t = w.tags
            if (t["tunnel"] == "yes" || t["tunnel"] == "building_passage" || (t["layer"]?.toIntOrNull() ?: 0) < 0) continue
            // Only ways that come near the route.
            var near = false
            var i = 0
            while (i < w.xy.size && !near) { near = nearest(w.xy[i], w.xy[i + 1], GROUND_RANGE) != null; i += 2 }
            if (!near) continue
            val xy = w.xy
            val ww = t["waterway"]
            val rail = t["railway"]
            val hw = t["highway"]
            when {
                ww != null -> {
                    val width = parseMeters(t["width"]) ?: when (ww) { "river" -> 18.0; "canal" -> 12.0; "stream" -> 3.0; else -> 1.5 }
                    ribbon(xy, width / 2, -width / 2, Lift.WATER, C_WATER)
                }
                rail in setOf("rail", "light_rail", "narrow_gauge", "tram") -> {
                    if (rail != "tram") ribbon(xy, 1.7, -1.7, Lift.PATH, C_RAIL_BED)
                    ribbon(xy, 0.80, 0.66, Lift.MARK, C_RAIL)
                    ribbon(xy, -0.66, -0.80, Lift.MARK, C_RAIL)
                }
                hw != null -> street(xy, hw, t)
            }
        }
    }

    private fun street(xy: DoubleArray, hw: String, t: Map<String, String>) {
        when (hw) {
            "footway", "path", "pedestrian", "steps", "bridleway", "corridor" -> {
                if (hw == "pedestrian" && t["area"] == "yes") return
                val width = parseMeters(t["width"]) ?: if (hw == "pedestrian") 6.0 else 2.0
                val colour = if (t["bicycle"] == "designated" && hw == "path") C_CYCLE else if (hw == "pedestrian") C_PAVING else C_PATH
                ribbon(xy, width / 2, -width / 2, Lift.PATH, colour)
            }
            "cycleway" -> { val w = parseMeters(t["width"]) ?: 2.2; ribbon(xy, w / 2, -w / 2, Lift.PATH, C_CYCLE) }
            "track" -> { val w = parseMeters(t["width"]) ?: 3.0; ribbon(xy, w / 2, -w / 2, Lift.PATH, C_TRACK) }
            else -> {
                val base = when (hw) {
                    "motorway", "motorway_link" -> 12.0
                    "trunk", "trunk_link" -> 10.0
                    "primary", "primary_link" -> 10.0
                    "secondary", "secondary_link" -> 8.5
                    "tertiary", "tertiary_link" -> 7.5
                    "residential", "unclassified" -> 6.0
                    "living_street" -> 5.0
                    "service" -> 3.6
                    "busway" -> 6.5
                    else -> return
                }
                val lanes = t["lanes"]?.toIntOrNull()
                val width = parseMeters(t["width"]) ?: lanes?.let { max(base * 0.8, it * 3.1) } ?: base
                val h = width / 2
                val hasSidewalk = hw in setOf("primary", "secondary", "tertiary", "residential", "unclassified", "living_street", "trunk") &&
                    t["sidewalk"] !in setOf("no", "none", "separate")
                if (hasSidewalk) {
                    ribbon(xy, h + 2.8, h - 0.2, Lift.SIDEWALK, C_SIDEWALK)
                    ribbon(xy, -h + 0.2, -h - 2.8, Lift.SIDEWALK, C_SIDEWALK)
                    // Kerb stones: a slim darker band at the road edge.
                    ribbon(xy, h + 0.25, h - 0.05, Lift.LANE, C_CURB)
                    ribbon(xy, -h + 0.05, -h - 0.25, Lift.LANE, C_CURB)
                }
                val paved = hw == "living_street" || t["surface"] in setOf("paving_stones", "sett", "cobblestone")
                ribbon(xy, h, -h, Lift.ASPHALT, if (paved) 0x8F8A84 else C_ASPHALT)
                val lane = listOf("cycleway", "cycleway:both", "cycleway:right", "cycleway:left")
                    .mapNotNull { k -> t[k]?.let { k to it } }
                    .filter { it.second == "lane" || it.second == "track" || it.second == "exclusive" }
                if (lane.isNotEmpty()) {
                    val left = lane.any { it.first == "cycleway:left" || it.first == "cycleway:both" || it.first == "cycleway" }
                    val right = lane.any { it.first == "cycleway:right" || it.first == "cycleway:both" || it.first == "cycleway" }
                    if (left) ribbon(xy, h - 0.1, h - 1.7, Lift.LANE, C_CYCLE)
                    if (right) ribbon(xy, -h + 1.7, -h + 0.1, Lift.LANE, C_CYCLE)
                }
                val rank = roadRank(hw)
                if (rank >= 3 && t["oneway"] != "yes" && !paved) ribbon(xy, 0.08, -0.08, Lift.MARK, C_MARKING, 3.0, 6.0)
                if (rank >= 4) {
                    ribbon(xy, h - 0.3, h - 0.45, Lift.MARK, C_MARKING)
                    ribbon(xy, -h + 0.45, -h + 0.3, Lift.MARK, C_MARKING)
                }
            }
        }
    }

    /**
     * A strip along a polyline between lateral offsets [left] and [right] (metres,
     * left of travel positive), draped on the terrain [lift] metres up. Optional
     * dashes ([on] / [off] metres). Each quad goes to the chunk of its midpoint.
     */
    private fun ribbon(xy: DoubleArray, left: Double, right: Double, lift: Double, rgb: Int, on: Double = 0.0, off: Double = 0.0) {
        // Resample to ≤ 4 m so the strip follows the terrain.
        val pts = FloatArr()
        val n0 = xy.size / 2
        for (i in 0 until n0 - 1) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]; val bx = xy[2 * i + 2]; val by = xy[2 * i + 3]
            val len = hypot(bx - ax, by - ay)
            val steps = max(1, kotlin.math.ceil(len / 4.0).toInt())
            for (k in 0 until steps) {
                val f = k.toDouble() / steps
                pts.add((ax + (bx - ax) * f).toFloat()); pts.add((ay + (by - ay) * f).toFloat())
            }
        }
        pts.add(xy[xy.size - 2].toFloat()); pts.add(xy[xy.size - 1].toFloat())
        val n = pts.size / 2
        if (n < 2) return
        val px = DoubleArray(n) { pts.a[2 * it].toDouble() }
        val py = DoubleArray(n) { pts.a[2 * it + 1].toDouble() }
        // Mitred left normals.
        val nx = DoubleArray(n); val ny = DoubleArray(n)
        for (i in 0 until n) {
            var sx = 0.0; var sy = 0.0
            if (i > 0) { val dx = px[i] - px[i - 1]; val dy = py[i] - py[i - 1]; val l = hypot(dx, dy).coerceAtLeast(1e-9); sx += -dy / l; sy += dx / l }
            if (i < n - 1) { val dx = px[i + 1] - px[i]; val dy = py[i + 1] - py[i]; val l = hypot(dx, dy).coerceAtLeast(1e-9); sx += -dy / l; sy += dx / l }
            val l = hypot(sx, sy).coerceAtLeast(1e-9)
            var mx = sx / l; var my = sy / l
            if (i in 1 until n - 1) {
                val dx = px[i + 1] - px[i]; val dy = py[i + 1] - py[i]; val ll = hypot(dx, dy).coerceAtLeast(1e-9)
                val cosHalf = (mx * (-dy / ll) + my * (dx / ll)).coerceAtLeast(0.5)
                mx /= cosHalf; my /= cosHalf
            }
            nx[i] = mx; ny[i] = my
        }
        var along = 0.0
        for (i in 0 until n - 1) {
            val seg = hypot(px[i + 1] - px[i], py[i + 1] - py[i])
            val mid = along + seg / 2
            along += seg
            if (on > 0 && mid % (on + off) >= on) continue
            val cx = (px[i] + px[i + 1]) / 2; val cy = (py[i] + py[i + 1]) / 2
            val near = nearest(cx, cy, GROUND_RANGE) ?: continue
            val m = chunkFor(near[0]).roads
            fun vert(k: Int, o: Double): Int {
                val x = px[k] + nx[k] * o; val y = py[k] + ny[k] * o
                return m.v(x, terrain.height(x, y) + lift, -y, 0.0, 1.0, 0.0, rgb)
            }
            val a = vert(i, left); val b = vert(i, right); val c = vert(i + 1, right); val d = vert(i + 1, left)
            m.tri(a, b, c); m.tri(a, c, d)
        }
    }

    private fun roadRank(hw: String) = when (hw.removeSuffix("_link")) {
        "service" -> 0; "living_street" -> 1; "residential", "unclassified" -> 2; "busway" -> 2
        "tertiary" -> 3; "secondary" -> 4; "primary" -> 5; "trunk" -> 6; "motorway" -> 7; else -> 0
    }

    private fun areaColour(t: Map<String, String>): Int? {
        t["amenity"]?.let { if (it == "parking") return C_PARKING }
        t["area:highway"]?.let { return if (it == "cycleway") C_CYCLE else C_PAVING }
        if (t["highway"] == "pedestrian") return C_PAVING
        t["natural"]?.let {
            return when (it) {
                "water" -> C_WATER; "wood" -> C_FOREST; "scrub", "heath" -> C_SCRUB; "grassland" -> C_GRASS
                "wetland" -> C_WETLAND; "sand", "beach" -> C_SAND; "bare_rock", "scree", "rock", "shingle" -> C_ROCK
                else -> null
            }
        }
        if (t["water"] != null) return C_WATER
        t["leisure"]?.let {
            return when (it) {
                "park", "garden", "recreation_ground" -> C_PARK
                "pitch" -> if (t["surface"] in setOf("sand", "dirt", "clay")) C_SAND_PITCH else 0x6DB35A
                "playground" -> C_SAND_PITCH
                "track" -> 0xC0705A
                else -> null
            }
        }
        t["landuse"]?.let {
            return when (it) {
                "residential" -> C_RESIDENTIAL
                "commercial", "retail" -> C_COMMERCIAL
                "industrial", "railway", "construction", "garages", "brownfield" -> C_INDUSTRIAL
                "grass", "meadow", "village_green", "recreation_ground", "flowerbed" -> C_GRASS
                "forest" -> C_FOREST
                "farmland", "orchard", "vineyard", "allotments" -> C_FARM
                "cemetery" -> 0x93B878
                "basin", "reservoir" -> C_WATER
                else -> null
            }
        }
        return null
    }

    /** Paint order: big land use first, finer surfaces on top. */
    private fun areaOrder(t: Map<String, String>): Int = when {
        t.containsKey("landuse") -> 10
        t.containsKey("leisure") -> 14
        t.containsKey("natural") && t["natural"] != "water" -> 16
        t["natural"] == "water" || t.containsKey("water") -> 30
        else -> 20
    }

    // ---------------------------------------------------------------------------------
    // Buildings

    private fun buildBuildings() {
        val parts = osm.areas.filter { it.tags.containsKey("building:part") && it.tags["building:part"] != "no" }
        val partCentres = parts.map { Geometry.centroid(it.outers[0]) }
        for (a in osm.areas) {
            val t = a.tags
            val isPart = t.containsKey("building:part") && t["building:part"] != "no"
            val isBuilding = t.containsKey("building") && t["building"] != "no"
            if (!isPart && !isBuilding) continue
            if ((t["layer"]?.toIntOrNull() ?: 0) < 0 || t["location"] == "underground") continue
            val outer = a.outers.maxByOrNull { abs(Geometry.area(it)) } ?: continue
            val c = Geometry.centroid(outer)
            val near = nearest(c[0], c[1], BUILDING_RANGE) ?: continue
            // A building drawn as parts: the parts carry the real shape.
            if (isBuilding && !isPart && partCentres.any { pc -> Geometry.contains(outer, pc[0], pc[1]) }) continue
            val chunk = chunkFor(near[0])
            for (ring in a.outers) building(chunk, a.id, t, ring)
        }
    }

    private fun building(chunk: Chunk, id: Long, t: Map<String, String>, raw: DoubleArray) {
        val ring = Geometry.simplify(Geometry.ccw(raw))
        val n = ring.size / 2
        if (n < 3 || abs(Geometry.area(ring, n)) < 6.0) return
        val type = t["building"] ?: t["building:part"] ?: "yes"
        val levels = t["building:levels"]?.toDoubleOrNull()
        val roofLevels = t["roof:levels"]?.toDoubleOrNull() ?: 0.0
        val minLevel = t["building:min_level"]?.toDoubleOrNull()
        val height = parseMeters(t["height"]) ?: levels?.let { it * FLOOR + roofLevels * 2.6 + 0.8 } ?: when (type) {
            "house", "detached", "semidetached_house", "bungalow", "terrace", "farm" -> 7.5
            "garage", "garages", "shed", "carport", "hut", "kiosk", "roof" -> 3.0
            "church", "cathedral" -> 20.0
            "industrial", "warehouse", "retail", "supermarket" -> 8.0
            "apartments", "residential" -> 16.0
            else -> 12.0
        }
        val minHeight = parseMeters(t["min_height"]) ?: minLevel?.let { it * FLOOR } ?: 0.0
        if (height <= minHeight + 0.5) return

        // Ground under the footprint.
        var ground = Double.MAX_VALUE
        for (i in 0 until n) ground = min(ground, terrain.height(ring[2 * i], ring[2 * i + 1]))
        val base = ground + minHeight
        val bottom = if (minHeight > 0) base else ground - 0.6
        val top = ground + height

        // Roof shape.
        val tagShape = t["roof:shape"]
        val house = type in setOf("house", "detached", "semidetached_house", "bungalow", "farm", "barn", "shed", "cabin", "farm_auxiliary", "garage", "church", "chapel")
        val shape = tagShape ?: if (house && n == 4 && abs(Geometry.area(ring, n)) < 600) "gabled" else "flat"
        val pitched = n == 4 && shape in setOf("gabled", "hipped", "pyramidal", "half-hipped", "gambrel", "mansard")
        val rect = if (pitched) rectAxes(ring) else null
        val roofH = if (pitched && rect != null) {
            parseMeters(t["roof:height"]) ?: (min(rect[2], rect[3]) / 2 * tan(Math.toRadians(36.0))).coerceAtMost(height * 0.5)
        } else 0.0
        val eave = top - roofH

        val seed = (id xor (id ushr 17)).toInt() and 0x7FFFFFFF
        val wall = parseColour(t["building:colour"]) ?: parseColour(t["colour"]) ?: WALLS[seed % WALLS.size]
        val roof = parseColour(t["roof:colour"]) ?: if (pitched) ROOF_PITCHED[(seed / 7) % ROOF_PITCHED.size] else ROOF_FLAT[(seed / 7) % ROOF_FLAT.size]
        val style = when {
            type in setOf("garage", "garages", "shed", "carport", "roof", "industrial", "warehouse", "hut") -> 0.0
            t.containsKey("shop") || type in setOf("retail", "commercial", "supermarket", "kiosk") -> 2.0
            else -> 1.0
        }

        // Walls (one quad per edge), UVs in bays / floors for the window shader.
        val w = chunk.walls
        var along = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = ring[2 * i]; val ay = ring[2 * i + 1]; val bx = ring[2 * j]; val by = ring[2 * j + 1]
            val len = hypot(bx - ax, by - ay)
            if (len < 0.05) continue
            // CCW ring: outward normal is to the right of travel (y north). Godot z = -north.
            val nx = (by - ay) / len; val nz = (bx - ax) / len
            val u0 = along / BAY; val u1 = (along + len) / BAY
            val v0 = (bottom - ground) / FLOOR; val v1 = (eave - ground) / FLOOR
            val s1 = len / BAY
            val p0 = w.v(ax, bottom, -ay, nx, 0.0, nz, wall, u0, v0, style, s1)
            val p1 = w.v(bx, bottom, -by, nx, 0.0, nz, wall, u1, v0, style, s1)
            val p2 = w.v(bx, eave, -by, nx, 0.0, nz, wall, u1, v1, style, s1)
            val p3 = w.v(ax, eave, -ay, nx, 0.0, nz, wall, u0, v1, style, s1)
            w.tri(p0, p1, p2); w.tri(p0, p2, p3)
            along += len
            // Ink: base + eave edges.
            edge(chunk, ax, bottom.coerceAtLeast(ground), ay, bx, bottom.coerceAtLeast(ground), by)
            edge(chunk, ax, eave, ay, bx, eave, by)
        }
        // Vertical corner edges where the outline actually turns.
        for (i in 0 until n) {
            val a = (i + n - 1) % n; val c = (i + 1) % n
            val d1x = ring[2 * i] - ring[2 * a]; val d1y = ring[2 * i + 1] - ring[2 * a + 1]
            val d2x = ring[2 * c] - ring[2 * i]; val d2y = ring[2 * c + 1] - ring[2 * i + 1]
            val ang = abs(atan2(d1x * d2y - d1y * d2x, d1x * d2x + d1y * d2y))
            if (ang > 0.35) edge(chunk, ring[2 * i], bottom.coerceAtLeast(ground), ring[2 * i + 1], ring[2 * i], eave, ring[2 * i + 1])
        }

        if (pitched && rect != null && roofH > 0.3) pitchedRoof(chunk, ring, rect, eave, top, wall, roof, shape)
        else flatRoof(chunk, ring, n, eave, roof)
    }

    /** For a 4-point ring: [cx, cy, length along long axis, width, ux, uy] of its rectangle. */
    private fun rectAxes(r: DoubleArray): DoubleArray? {
        val e0 = hypot(r[2] - r[0], r[3] - r[1]); val e1 = hypot(r[4] - r[2], r[5] - r[3])
        val e2 = hypot(r[6] - r[4], r[7] - r[5]); val e3 = hypot(r[0] - r[6], r[1] - r[7])
        if (abs(e0 - e2) > 0.25 * max(e0, e2) || abs(e1 - e3) > 0.25 * max(e1, e3)) return null
        val c = Geometry.centroid(r, 4)
        return if (e0 >= e1) {
            doubleArrayOf(c[0], c[1], (e0 + e2) / 2, (e1 + e3) / 2, (r[2] - r[0]) / e0, (r[3] - r[1]) / e0)
        } else {
            doubleArrayOf(c[0], c[1], (e1 + e3) / 2, (e0 + e2) / 2, (r[4] - r[2]) / e1, (r[5] - r[3]) / e1)
        }
    }

    private fun flatRoof(chunk: Chunk, ring: DoubleArray, n: Int, h: Double, rgb: Int) {
        val m = chunk.roofs
        val start = m.vertexCount
        for (i in 0 until n) m.v(ring[2 * i], h, -ring[2 * i + 1], 0.0, 1.0, 0.0, rgb)
        Geometry.triangulate(ring, n) { a, b, c -> m.tri(start + a, start + c, start + b) }
    }

    /** Gabled / hipped roof over a near-rectangular footprint, ridge along the long axis. */
    private fun pitchedRoof(chunk: Chunk, ring: DoubleArray, rc: DoubleArray, eave: Double, top: Double, wall: Int, rgb: Int, shape: String) {
        val cx = rc[0]; val cy = rc[1]; val halfL = rc[2] / 2; val halfW = rc[3] / 2
        val ux = rc[4]; val uy = rc[5]; val vx = -uy; val vy = ux
        val hip = if (shape == "gabled") 0.0 else if (shape == "pyramidal") halfL else min(halfW, halfL * 0.5)
        // Corners of the eave rectangle (aligned) and ridge ends.
        fun p(a: Double, b: Double) = doubleArrayOf(cx + ux * a + vx * b, cy + uy * a + vy * b)
        val c00 = p(-halfL, -halfW); val c10 = p(halfL, -halfW); val c11 = p(halfL, halfW); val c01 = p(-halfL, halfW)
        val r0 = p(-halfL + hip, 0.0); val r1 = p(halfL - hip, 0.0)
        val m = chunk.roofs
        fun face(pts: List<DoubleArray>, hs: List<Double>, colour: Int) {
            // Normal from the first three points (Godot space).
            val ax = pts[0][0]; val ay = hs[0]; val az = -pts[0][1]
            val bx = pts[1][0]; val by = hs[1]; val bz = -pts[1][1]
            val qx = pts[2][0]; val qy = hs[2]; val qz = -pts[2][1]
            var nx = (by - ay) * (qz - az) - (bz - az) * (qy - ay)
            var ny = (bz - az) * (qx - ax) - (bx - ax) * (qz - az)
            var nz = (bx - ax) * (qy - ay) - (by - ay) * (qx - ax)
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9)
            nx /= l; ny /= l; nz /= l
            if (ny < 0) { nx = -nx; ny = -ny; nz = -nz }
            val start = m.vertexCount
            pts.forEachIndexed { i, q -> m.v(q[0], hs[i], -q[1], nx, ny, nz, colour) }
            for (i in 1 until pts.size - 1) m.tri(start, start + i, start + i + 1)
        }
        // Long sides.
        face(listOf(c00, c10, r1, r0), listOf(eave, eave, top, top), rgb)
        face(listOf(c11, c01, r0, r1), listOf(eave, eave, top, top), rgb)
        // Ends: hips (roof colour) or gables (wall colour).
        if (hip > 0.01) {
            face(listOf(c10, c11, r1), listOf(eave, eave, top), rgb)
            face(listOf(c01, c00, r0), listOf(eave, eave, top), rgb)
        } else {
            face(listOf(c10, c11, r1), listOf(eave, eave, top), wall)
            face(listOf(c01, c00, r0), listOf(eave, eave, top), wall)
        }
        edge(chunk, r0[0], top, r0[1], r1[0], top, r1[1])
        for ((c, r) in listOf(c00 to r0, c01 to r0, c10 to r1, c11 to r1)) edge(chunk, c[0], eave, c[1], r[0], top, r[1])
    }

    /** An ink line (local x, height, local y) in Godot space. */
    private fun edge(chunk: Chunk, ax: Double, ah: Double, ay: Double, bx: Double, bh: Double, by: Double) {
        val e = chunk.edges
        e.add(ax.toFloat()); e.add(ah.toFloat()); e.add((-ay).toFloat())
        e.add(bx.toFloat()); e.add(bh.toFloat()); e.add((-by).toFloat())
    }

    // ---------------------------------------------------------------------------------
    // Props

    private fun prop(type: String, x: Double, y: Double, scale: Double, rot: Double, variant: Int = 0) {
        val near = nearest(x, y, PROP_RANGE) ?: return
        val c = chunkFor(near[0])
        if (c.propCount > 0) c.props.append(',')
        c.props.append("{\"t\":\"$type\",\"p\":[${fmt(x)},${fmt(terrain.height(x, y))},${fmt(-y)}],\"s\":${fmt(scale)},\"r\":${fmt(rot)},\"k\":$variant}")
        c.propCount++
    }

    private fun buildProps() {
        val treePts = ArrayList<DoubleArray>()
        for (p in osm.points) {
            val t = p.tags
            val rot = ((p.id * 2654435761L) and 0xFFFF) / 65535.0 * 2 * PI
            val v = ((p.id ushr 3) and 7).toInt()
            when {
                t["natural"] == "tree" -> {
                    val h = parseMeters(t["height"]) ?: (8.0 + v)
                    prop(if (t["leaf_type"] == "needleleaved") "conifer" else "tree", p.x, p.y, h / 10.0, rot, v)
                    treePts.add(doubleArrayOf(p.x, p.y))
                }
                t["highway"] == "street_lamp" -> prop("lamp", p.x, p.y, 1.0, rot, v)
                t["amenity"] == "bench" || t["leisure"] == "picnic_table" -> prop("bench", p.x, p.y, 1.0, rot, v)
                t["highway"] == "bus_stop" || t["public_transport"] == "platform" -> prop("bus_stop", p.x, p.y, 1.0, rot, v)
                t["highway"] == "traffic_signals" || t["crossing"] == "traffic_signals" -> prop("signal", p.x, p.y, 1.0, rot, v)
                t["barrier"] == "bollard" -> prop("bollard", p.x, p.y, 1.0, rot, v)
                t["amenity"] == "bicycle_parking" -> prop("bike_rack", p.x, p.y, 1.0, rot, v)
                t["amenity"] == "waste_basket" -> prop("bin", p.x, p.y, 1.0, rot, v)
                t["emergency"] == "fire_hydrant" -> prop("hydrant", p.x, p.y, 1.0, rot, v)
            }
        }
        // Tree rows.
        for (w in osm.ways) if (w.tags["natural"] == "tree_row") {
            var carry = 0.0
            for (i in 0 until w.xy.size / 2 - 1) {
                val ax = w.xy[2 * i]; val ay = w.xy[2 * i + 1]; val bx = w.xy[2 * i + 2]; val by = w.xy[2 * i + 3]
                val len = hypot(bx - ax, by - ay)
                var s = carry
                while (s < len) {
                    val x = ax + (bx - ax) * s / len; val y = ay + (by - ay) * s / len
                    val k = (x * 7 + y * 13).toInt()
                    prop("tree", x, y, 0.9 + (k and 3) * 0.08, (k and 63) / 10.0, k and 7)
                    treePts.add(doubleArrayOf(x, y))
                    s += 9.0
                }
                carry = s - len
            }
        }
        // Woods and parks without mapped trees: scatter.
        val buildingRings = osm.areas.filter { it.tags.containsKey("building") }.map { it.outers[0] to Geometry.bbox(it.outers) }
        for (a in osm.areas) {
            val t = a.tags
            val dense = t["natural"] == "wood" || t["landuse"] == "forest"
            val sparse = t["leisure"] in setOf("park", "garden") || t["landuse"] in setOf("cemetery", "grass", "village_green")
            if (!dense && !sparse) continue
            val ring = a.outers.maxByOrNull { abs(Geometry.area(it)) } ?: continue
            val bb = Geometry.bbox(listOf(ring))
            val mapped = treePts.any { p -> p[0] in bb[0]..bb[2] && p[1] in bb[1]..bb[3] && Geometry.contains(ring, p[0], p[1]) }
            if (mapped && !dense) continue
            val step = if (dense) 6.0 else 13.0
            var y = floor(bb[1] / step) * step
            while (y < bb[3]) {
                var x = floor(bb[0] / step) * step
                while (x < bb[2]) {
                    val hx = ((x * 73856093).toLong() xor (y * 19349663).toLong())
                    val jx = x + ((hx and 0xFF) / 255.0) * step
                    val jy = y + (((hx shr 8) and 0xFF) / 255.0) * step
                    val keep = ((hx shr 16) and 0xFF) / 255.0 < (if (dense) 0.85 else 0.35)
                    if (keep && Geometry.contains(ring, jx, jy) && a.inners.none { Geometry.contains(it, jx, jy) } &&
                        buildingRings.none { (r, b) -> jx in b[0]..b[2] && jy in b[1]..b[3] && Geometry.contains(r, jx, jy) } &&
                        nearest(jx, jy, PROP_RANGE) != null
                    ) {
                        val conifer = dense && ((hx shr 24) and 3) == 0L
                        prop(if (conifer) "conifer" else "tree", jx, jy, 0.8 + ((hx shr 26) and 7) / 14.0, ((hx shr 4) and 63) / 10.0, ((hx shr 12) and 7).toInt())
                    }
                    x += step
                }
                y += step
            }
        }
    }
}
