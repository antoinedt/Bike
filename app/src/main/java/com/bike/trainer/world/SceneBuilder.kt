package com.bike.trainer.world

import com.bike.trainer.route.Route
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
    /** What the Street View photos say about each building (none without photos). */
    private val facts: (OsmArea) -> BuildingFacts? = { null },
    /** Trees seen in the photos (local x, y), added where the map has none. */
    private val photoTrees: List<DoubleArray> = emptyList(),
) {
    private val region = route.points.firstOrNull()?.let { Region.of(it.lat, it.lon) } ?: Region.OTHER

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
        const val VERSION = 3
        /** Street half width + sidewalk, for the room in front of façades (Montreal yards). */
        private const val STREET_HALF = 6.5
        private val TREES = setOf("tree", "conifer", "maple", "linden", "ash", "locust", "elm", "oak", "poplar", "birch", "small_tree")
        const val CHUNK_LEN = 400.0
        private const val TILE = 160.0
        private const val TEX = 512
        private const val GRID = 8.0
        private const val GROUND_RANGE = 280.0
        private const val BUILDING_RANGE = 230.0
        private const val PROP_RANGE = 160.0
        private const val CELL = 20.0
        private const val FIELD_RANGE = 440.0
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
        val rails = MeshData()
        val edges = FloatArr()
        val props = StringBuilder()
        var propCount = 0
    }

    // Route samples every 5 m (for the manifest).
    private val rs = ArrayList<DoubleArray>() // [d, x, y]

    /**
     * "Nearest point on the route" for every 20 m cell near the route, computed once
     * (cell -> [distance along route, distance from route]). Queries are then a
     * single lookup instead of a search, which matters on big city routes.
     */
    private val field = HashMap<Long, DoubleArray>()

    /** The chunk being built (the world is built and written one chunk at a time). */
    private var current = -1
    private var cur = Chunk()

    init {
        var d = 0.0
        while (true) {
            val p = route.pointAt(d)
            rs.add(doubleArrayOf(d, p.x, p.y))
            if (d >= route.totalDistance) break
            d = min(d + 5.0, route.totalDistance)
        }
        val reach = (FIELD_RANGE / CELL).toInt() + 1
        var k = 0
        while (k < rs.size) {
            val s = rs[k]
            val cx = floor(s[1] / CELL).toLong(); val cy = floor(s[2] / CELL).toLong()
            for (i in cx - reach..cx + reach) for (j in cy - reach..cy + reach) {
                val dist = hypot((i + 0.5) * CELL - s[1], (j + 0.5) * CELL - s[2])
                if (dist > FIELD_RANGE + CELL) continue
                val key = (i shl 32) or (j and 0xFFFFFFFFL)
                val e = field[key]
                if (e == null) field[key] = doubleArrayOf(s[0], dist)
                else if (dist < e[1]) { e[0] = s[0]; e[1] = dist }
            }
            k += 4 // every 20 m is plenty for 20 m cells
        }
    }

    private fun key(x: Double, y: Double, cell: Double): Long =
        (floor(x / cell).toLong() shl 32) or (floor(y / cell).toLong() and 0xFFFFFFFFL)

    /** Carriageway width of a drivable street (null for paths, rail …). */
    private fun carriageway(t: Map<String, String>): Double? {
        val base = when (t["highway"]) {
            "motorway", "motorway_link" -> 12.0
            "trunk", "trunk_link" -> 10.0
            "primary", "primary_link" -> 10.0
            "secondary", "secondary_link" -> 8.5
            "tertiary", "tertiary_link" -> 7.5
            // North American residential streets are wide (parking both sides).
            "residential", "unclassified" -> if (region == Region.QUEBEC || region == Region.NORTH_AMERICA) 9.0 else 6.0
            "living_street" -> 5.0
            "service" -> 3.6
            "busway" -> 6.5
            else -> return null
        }
        val lanes = t["lanes"]?.toIntOrNull()
        return parseMeters(t["width"]) ?: lanes?.let { max(base * 0.8, it * 3.1) } ?: base
    }

    /** Drivable street segments [ax, ay, bx, by, half width] on a 20 m grid (props keep off them). */
    private val roadGrid: HashMap<Long, MutableList<DoubleArray>> by lazy {
        val g = HashMap<Long, MutableList<DoubleArray>>()
        for (w in osm.ways) {
            if (w.tags["tunnel"] == "yes" || w.tags["area"] == "yes") continue
            val half = (carriageway(w.tags) ?: continue) / 2
            for (i in 0 until w.xy.size / 2 - 1) {
                val seg = doubleArrayOf(w.xy[2 * i], w.xy[2 * i + 1], w.xy[2 * i + 2], w.xy[2 * i + 3], half)
                val x0 = min(seg[0], seg[2]) - half; val x1 = max(seg[0], seg[2]) + half
                val y0 = min(seg[1], seg[3]) - half; val y1 = max(seg[1], seg[3]) + half
                if (nearest((x0 + x1) / 2, (y0 + y1) / 2, PROP_RANGE + 200) == null) continue
                for (i2 in floor(x0 / CELL).toLong()..floor(x1 / CELL).toLong()) for (j in floor(y0 / CELL).toLong()..floor(y1 / CELL).toLong()) {
                    g.getOrPut((i2 shl 32) or (j and 0xFFFFFFFFL)) { ArrayList() }.add(seg)
                }
            }
        }
        g
    }

    /**
     * How far (x, y) is outside the nearest carriageway (negative = on the road), and
     * the point moved onto that road's kerb plus [kerb] metres: [clearance, kx, ky].
     */
    private fun roadClearance(x: Double, y: Double, kerb: Double = 0.6): DoubleArray {
        val list = roadGrid[(floor(x / CELL).toLong() shl 32) or (floor(y / CELL).toLong() and 0xFFFFFFFFL)]
            ?: return doubleArrayOf(99.0, x, y)
        var best = 99.0; var kx = x; var ky = y
        for (s in list) {
            val dx = s[2] - s[0]; val dy = s[3] - s[1]
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-9) 0.0 else (((x - s[0]) * dx + (y - s[1]) * dy) / l2).coerceIn(0.0, 1.0)
            val px = s[0] + dx * t; val py = s[1] + dy * t
            val d = hypot(x - px, y - py)
            val c = d - s[4]
            if (c < best) {
                best = c
                val nx: Double; val ny: Double
                if (d > 1e-6) { nx = (x - px) / d; ny = (y - py) / d } else { val l = kotlin.math.sqrt(l2).coerceAtLeast(1e-9); nx = -dy / l; ny = dx / l }
                kx = px + nx * (s[4] + kerb); ky = py + ny * (s[4] + kerb)
            }
        }
        return doubleArrayOf(best, kx, ky)
    }

    /** Exact distance from the route line (the field lookup is only good to a cell). */
    private fun routeDistance(x: Double, y: Double): Double {
        val e = nearest(x, y, PROP_RANGE + 100) ?: return Double.MAX_VALUE
        val i0 = ((e[0] - 60) / 5).toInt().coerceIn(0, rs.size - 1)
        val i1 = ((e[0] + 60) / 5).toInt().coerceIn(0, rs.size - 1)
        var best = Double.MAX_VALUE
        for (i in i0 until i1) {
            val a = rs[i]; val b = rs[i + 1]
            val dx = b[1] - a[1]; val dy = b[2] - a[2]
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-9) 0.0 else (((x - a[1]) * dx + (y - a[2]) * dy) / l2).coerceIn(0.0, 1.0)
            best = min(best, hypot(x - a[1] - dx * t, y - a[2] - dy * t))
        }
        return best
    }

    /** (distance along route, distance from route) of the nearest route point, ±10 m. */
    private fun nearest(x: Double, y: Double, maxRange: Double): DoubleArray? {
        val e = field[(floor(x / CELL).toLong() shl 32) or (floor(y / CELL).toLong() and 0xFFFFFFFFL)] ?: return null
        return if (e[1] <= maxRange) e else null
    }

    private fun chunkIndex(d: Double) = floor(d / CHUNK_LEN).toInt()

    // ---------------------------------------------------------------------------------
    // Entry point

    /**
     * Build everything; [write] receives each output file (name, bytes). Features are
     * first sorted into the chunks they touch, then each chunk is built, written and
     * released before the next, so memory stays at one chunk whatever the route length.
     */
    fun build(write: (String, ByteArray) -> Unit, progress: (String) -> Unit = {}) {
        progress("Sorting map data")
        val groundTiles = groundTilesByChunk()
        val ways = waysByChunk()
        val buildings = buildingsByChunk()
        val props = propsByChunk()
        val indices = (groundTiles.keys + ways.keys + buildings.keys + props.keys).toSortedSet()
        val infos = ArrayList<ChunkInfo>()
        for ((n, index) in indices.withIndex()) {
            progress("Chunk ${n + 1}/${indices.size}")
            current = index
            cur = Chunk()
            groundTiles[index]?.forEach { paintTile(it) }
            ways[index]?.forEach { linework(it) }
            buildings[index]?.forEach { (a, ring) -> building(cur, a, ring) }
            props[index]?.let { cur.props.append(it.first); cur.propCount = it.second }
            val glb = GlbWriter()
            cur.ground.forEach { (name, g) -> glb.add(name, g.first, "ground", g.second) }
            glb.add("walls", cur.walls, "walls")
            glb.add("roofs", cur.roofs, "roofs")
            glb.add("roads", cur.roads, "roads")
            glb.add("rails", cur.rails, "rails")
            val name = "chunk_%03d".format(index)
            if (!glb.isEmpty()) write("$name.glb", glb.bytes())
            val edges = StringBuilder()
            for (i in 0 until cur.edges.size) {
                if (i > 0) edges.append(',')
                edges.append(fmt(cur.edges.a[i].toDouble()))
            }
            write("$name.json", "{\"edges\":[$edges],\"props\":[${cur.props}]}".toByteArray())
            infos.add(ChunkInfo(index, index * CHUNK_LEN, (index + 1) * CHUNK_LEN))
            cur = Chunk() // release this chunk's geometry
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

    private val groundFeatureList by lazy { groundFeatures().sortedBy { it.order } }

    /** Ground tiles touching the corridor, by the chunk nearest to their centre. */
    private fun groundTilesByChunk(): Map<Int, List<Long>> {
        val tiles = HashSet<Long>()
        val r = (GROUND_RANGE / TILE).toInt() + 1
        for (s in rs) {
            val tx = floor(s[1] / TILE).toLong(); val ty = floor(s[2] / TILE).toLong()
            for (i in tx - r..tx + r) for (j in ty - r..ty + r) tiles.add((i shl 32) or (j and 0xFFFFFFFFL))
        }
        val out = HashMap<Int, MutableList<Long>>()
        for (t in tiles) {
            val ix = (t shr 32).toInt(); val iy = t.toInt()
            // Nearest over the tile's centre and corners (it may only clip the corridor).
            var best: DoubleArray? = null
            for ((fx, fy) in listOf(0.5 to 0.5, 0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)) {
                val nr = nearest((ix + fx) * TILE, (iy + fy) * TILE, GROUND_RANGE) ?: continue
                if (best == null || nr[1] < best[1]) best = nr
            }
            val b = best ?: continue
            out.getOrPut(chunkIndex(b[0])) { ArrayList() }.add(t)
        }
        return out
    }

    private fun paintTile(t: Long) {
        val ix = (t shr 32).toInt(); val iy = t.toInt()
        val ox = ix * TILE; val oy = iy * TILE
        val raster = Raster(TEX, ox, oy, TILE)
        raster.fill(C_MEADOW)
        for (f in groundFeatureList) {
            val b = f.bbox
            if (b[2] < ox || b[0] > ox + TILE || b[3] < oy || b[1] > oy + TILE) continue
            f.draw(raster)
        }
        cur.ground.add("ground_${ix}_$iy" to (groundMesh(ox, oy) to raster.png()))
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

    /** Ways that come near the route, by every chunk they can contribute to. */
    private fun waysByChunk(): Map<Int, List<OsmWay>> {
        val out = HashMap<Int, MutableList<OsmWay>>()
        for (w in osm.ways) {
            val t = w.tags
            if (t["tunnel"] == "yes" || t["tunnel"] == "building_passage" || (t["layer"]?.toIntOrNull() ?: 0) < 0) continue
            if (t["waterway"] == null && t["railway"] == null && t["highway"] == null) continue
            val set = HashSet<Int>()
            var i = 0
            while (i < w.xy.size) {
                // Sample long segments too, so a way crossing the corridor isn't missed.
                if (i + 2 < w.xy.size) {
                    val ax = w.xy[i]; val ay = w.xy[i + 1]; val bx = w.xy[i + 2]; val by = w.xy[i + 3]
                    val steps = max(1, (hypot(bx - ax, by - ay) / CELL).toInt())
                    for (k in 0 until steps) {
                        val f = k.toDouble() / steps
                        nearest(ax + (bx - ax) * f, ay + (by - ay) * f, GROUND_RANGE)?.let { set.add(chunkIndex(it[0])) }
                    }
                } else nearest(w.xy[i], w.xy[i + 1], GROUND_RANGE)?.let { set.add(chunkIndex(it[0])) }
                i += 2
            }
            for (c in set) out.getOrPut(c) { ArrayList() }.add(w)
        }
        return out
    }

    private fun linework(w: OsmWay) {
        val t = w.tags
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
                val width = carriageway(t) ?: return
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
            if (chunkIndex(near[0]) != current) continue
            val m = cur.roads
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

    /** Building footprints near the route, by the chunk nearest to them. */
    private fun buildingsByChunk(): Map<Int, List<Pair<OsmArea, DoubleArray>>> {
        val parts = osm.areas.filter { it.tags.containsKey("building:part") && it.tags["building:part"] != "no" }
        val partCentres = parts.map { Geometry.centroid(it.outers[0]) }
        val out = HashMap<Int, MutableList<Pair<OsmArea, DoubleArray>>>()
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
            val list = out.getOrPut(chunkIndex(near[0])) { ArrayList() }
            for (ring in a.outers) list.add(a to ring)
        }
        return out
    }

    private fun building(chunk: Chunk, area: OsmArea, raw: DoubleArray) {
        val id = area.id
        val t = area.tags
        val ring = Geometry.simplify(Geometry.ccw(raw))
        val n = ring.size / 2
        val footprint = abs(Geometry.area(ring, n))
        if (n < 3 || footprint < 6.0) return
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
        val isPart = t.containsKey("building:part")

        // Style from tags, size, region and the photos.
        val f = facts(area)
        val nLevels = estimateLevels(levels, height - minHeight)
        val seed = (id xor (id ushr 17)).toInt() and 0x7FFFFFFF
        val classified = if (isPart && t["building"] == null) ArchStyle.BLANK.takeIf { height - minHeight < 4 } ?: ArchStyle.classify(type, t, height, nLevels, footprint, longestSide(ring), region, f)
        else ArchStyle.classify(type, t, height, nLevels, footprint, longestSide(ring), region, f)
        // Montreal without a photo verdict: about a third of the plexes are greystones.
        val style = if (classified == ArchStyle.PLEX && region == Region.QUEBEC && f?.material == null && seed % 3 == 0) ArchStyle.GREYSTONE else classified
        val montreal = region == Region.QUEBEC && (style == ArchStyle.PLEX || style == ArchStyle.GREYSTONE)

        // Roof shape: tagged, or obvious for houses / churches on simple footprints.
        val tagShape = t["roof:shape"]
        val wantsPitch = style == ArchStyle.HOUSE || style == ArchStyle.CHURCH ||
            type in setOf("barn", "farm_auxiliary", "cabin", "garage", "shed")
        val shape = tagShape ?: if (wantsPitch && n == 4 && footprint < 1500) "gabled" else "flat"
        val pitched = n == 4 && shape in setOf("gabled", "hipped", "pyramidal", "half-hipped", "gambrel", "mansard")
        val radial = !pitched && shape in setOf("dome", "onion", "cone", "pyramidal", "round")
        val rect = if (pitched) rectAxes(ring) else null
        val roofH = when {
            pitched && rect != null -> parseMeters(t["roof:height"])
                ?: (min(rect[2], rect[3]) / 2 * tan(Math.toRadians(if (style == ArchStyle.CHURCH) 50.0 else 36.0))).coerceAtMost(height * 0.55)
            radial -> parseMeters(t["roof:height"]) ?: (Math.sqrt(footprint / PI) * if (shape == "cone") 2.0 else 1.0).coerceAtMost(height * 0.6)
            else -> 0.0
        }
        val eave = top - roofH

        val wall = parseColour(t["building:colour"]) ?: parseColour(t["colour"]) ?: f?.colour ?: style.palette[seed % style.palette.size]
        val roof = parseColour(t["roof:colour"]) ?: if (pitched || radial) ROOF_PITCHED[(seed / 7) % ROOF_PITCHED.size] else ROOF_FLAT[(seed / 7) % ROOF_FLAT.size]
        val shop = t.containsKey("shop") || type in setOf("retail", "commercial", "supermarket", "kiosk")
        val floorH = clampFloor(eave - base, nLevels)

        // Walls (one quad per edge). UVs in bays / floors for the façade shader:
        // UV1 = (style id, wall length in bays); colour alpha < 1 = shopfront ground floor.
        val w = chunk.walls
        val detail = Details(w, { ax, ah, ay, bx, bh, by -> edge(chunk, ax, ah, ay, bx, bh, by) }, chunk.rails)
        // Montreal cornices: mostly dark sheet metal, some painted light on red brick.
        val cornice = if (style == ArchStyle.PLEX && seed % 4 == 1) 0xD9D4C7 else 0x3C3E44
        var along = 0.0
        var balconies = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = ring[2 * i]; val ay = ring[2 * i + 1]; val bx = ring[2 * j]; val by = ring[2 * j + 1]
            val len = hypot(bx - ax, by - ay)
            if (len < 0.05) continue
            // CCW ring: outward normal is to the right of travel (y north). Godot z = -north.
            val nx = (by - ay) / len; val nz = (bx - ax) / len
            val u0 = along / style.bay; val u1 = (along + len) / style.bay
            val v0 = (bottom - ground) / floorH; val v1 = (eave - ground) / floorH
            val s1 = len / style.bay
            val sid = style.id.toDouble()
            val a = if (shop) 0.5f else 1f
            val p0 = w.v(ax, bottom, -ay, nx, 0.0, nz, wall, u0, v0, sid, s1, a)
            val p1 = w.v(bx, bottom, -by, nx, 0.0, nz, wall, u1, v0, sid, s1, a)
            val p2 = w.v(bx, eave, -by, nx, 0.0, nz, wall, u1, v1, sid, s1, a)
            val p3 = w.v(ax, eave, -ay, nx, 0.0, nz, wall, u0, v1, sid, s1, a)
            w.tri(p0, p1, p2); w.tri(p0, p2, p3)
            along += len
            edge(chunk, ax, bottom.coerceAtLeast(ground), ay, bx, bottom.coerceAtLeast(ground), by)
            edge(chunk, ax, eave, ay, bx, eave, by)

            // Balconies / galleries on the walls that face the route close by.
            if (minHeight > 0 || nLevels < 2 || balconies > 60) continue
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            val nr = nearest(mx, my, 40.0) ?: continue
            if (nr[1] > 35.0) continue
            val rp = route.pointAt(nr[0])
            val ox = (by - ay) / len; val oy = -(bx - ax) / len // outward, local metres
            if ((rp.x - mx) * ox + (rp.y - my) * oy < 0.4 * hypot(rp.x - mx, rp.y - my)) continue
            val ux = (bx - ax) / len; val uy = (by - ay) / len
            if (montreal) {
                // Room in front of the façade before the sidewalk (street half + sidewalk ~ 6.5 m).
                val free = (rp.x - mx) * ox + (rp.y - my) * oy - STREET_HALF
                balconies += plexFront(detail, ax, ay, ux, uy, ox, oy, len, ground, floorH, min(nLevels, 4), eave, cornice, free, seed + i)
                continue
            }
            when (style) {
                ArchStyle.HISTORIC -> {
                    val bays = (len / style.bay).toInt()
                    if (bays < 3) continue
                    val trim = Rgb.lighter(wall, 0.18)
                    for (fl in 1 until nLevels) for (b in 0 until bays) {
                        if ((b + fl) % 3 != 0 || b == 0 || b == bays - 1) continue
                        val c = (b + 0.5) * len / bays
                        val cx = ax + ux * c; val cy = ay + uy * c
                        val h = ground + fl * floorH
                        detail.box(cx + ox * 0.45, cy + oy * 0.45, h + 0.06, ux, uy, ox, oy, 0.34 * style.bay, 0.45, 0.07, trim)
                        detail.box(cx + ox * 0.88, cy + oy * 0.88, h + 0.55, ux, uy, ox, oy, 0.34 * style.bay, 0.03, 0.42, 0x3A3F48, ink = false)
                        balconies++
                    }
                }
                ArchStyle.PLEX -> if (len <= 22.0) {
                    val trim = 0x3A3F48
                    for (fl in 1 until nLevels) {
                        val h = ground + fl * floorH
                        val mxx = (ax + bx) / 2; val myy = (ay + by) / 2
                        detail.box(mxx + ox * 0.75, myy + oy * 0.75, h + 0.06, ux, uy, ox, oy, len / 2 - 0.4, 0.75, 0.07, Rgb.darker(wall, 0.35))
                        detail.box(mxx + ox * 1.47, myy + oy * 1.47, h + 0.55, ux, uy, ox, oy, len / 2 - 0.4, 0.03, 0.42, trim, ink = false)
                        balconies++
                    }
                }
                else -> {}
            }
        }
        // Vertical corner edges where the outline actually turns.
        for (i in 0 until n) {
            val a = (i + n - 1) % n; val c = (i + 1) % n
            val d1x = ring[2 * i] - ring[2 * a]; val d1y = ring[2 * i + 1] - ring[2 * a + 1]
            val d2x = ring[2 * c] - ring[2 * i]; val d2y = ring[2 * c + 1] - ring[2 * i + 1]
            val ang = abs(atan2(d1x * d2y - d1y * d2x, d1x * d2x + d1y * d2y))
            if (ang > 0.35) edge(chunk, ring[2 * i], bottom.coerceAtLeast(ground), ring[2 * i + 1], ring[2 * i], eave, ring[2 * i + 1])
        }

        // Projecting bands: cornices, ledges, parapets.
        if (minHeight <= 0 && n <= 64) when (style) {
            ArchStyle.HISTORIC -> {
                val trim = Rgb.lighter(wall, 0.15)
                detail.band(ring, eave - 0.55, eave, 0.32, trim)
                if (nLevels >= 2) detail.band(ring, base + floorH - 0.08, base + floorH + 0.18, 0.12, trim)
            }
            ArchStyle.PLEX, ArchStyle.GREYSTONE -> if (montreal) {
                // The Montreal cornice: deep, dark, with a parapet above.
                detail.band(ring, eave - 0.7, eave, 0.5, cornice)
                detail.band(ring, eave, eave + 0.35, 0.22, cornice)
            } else detail.band(ring, eave - 0.45, eave + 0.25, 0.22, Rgb.darker(wall, 0.35))
            ArchStyle.SLAB -> detail.band(ring, eave - 0.2, eave + 0.35, 0.06, Rgb.lighter(wall, 0.1))
            ArchStyle.GLASS -> detail.band(ring, eave - 0.1, eave + 0.6, 0.04, 0x4A5260)
            ArchStyle.INDUSTRIAL -> detail.band(ring, eave - 0.1, eave + 0.4, 0.05, Rgb.darker(wall, 0.15))
            else -> {}
        }

        when {
            pitched && rect != null && roofH > 0.3 -> {
                pitchedRoof(chunk, ring, rect, eave, top, wall, roof, shape)
                if (style == ArchStyle.HOUSE) {
                    // A chimney.
                    val cx = rect[0] + rect[4] * rect[2] * 0.25; val cy = rect[1] + rect[5] * rect[2] * 0.25
                    detail.box(cx, cy, top - roofH * 0.3, rect[4], rect[5], -rect[5], rect[4], 0.3, 0.3, roofH * 0.45 + 0.4, 0x8A5A48)
                }
            }
            radial && roofH > 0.3 -> radialRoof(chunk, ring, n, eave, roofH, roof, shape)
            else -> flatRoof(chunk, ring, n, eave, roof)
        }
    }

    /**
     * A Montreal plex frontage along one street-facing wall (a → length [len] along
     * u, outwards o): cornice brackets and crest, balconies with iron railings and
     * the outdoor staircase up to the second floor, straight out into the yard when
     * there is room ([free] metres before the sidewalk) or along the façade, plus a
     * little fence and hedge. Returns the number of balconies made.
     */
    private fun plexFront(
        d: Details, ax: Double, ay: Double, ux: Double, uy: Double, ox: Double, oy: Double, len: Double,
        ground: Double, floorH: Double, levels: Int, eave: Double, cornice: Int, free: Double, seed: Int,
    ): Int {
        fun px(s: Double, off: Double) = ax + ux * s + ox * off
        fun py(s: Double, off: Double) = ay + uy * s + oy * off
        fun box(s: Double, off: Double, h: Double, halfAlong: Double, halfOut: Double, halfUp: Double, rgb: Int, ink: Boolean = true, skip: Int = Details.BACK) =
            d.box(px(s, off), py(s, off), h, ux, uy, ox, oy, halfAlong, halfOut, halfUp, rgb, ink, skip)
        val treadSkip = Details.BACK or Details.BOTTOM or Details.SIDES
        fun rail(s0: Double, o0: Double, h0: Double, s1: Double, o1: Double, h1: Double, height: Double = 1.0) =
            d.rail(px(s0, o0), py(s0, o0), h0, px(s1, o1), py(s1, o1), h1, height)

        // Cornice brackets all along, a crest over each unit.
        val nb = (len / 0.9).toInt()
        for (k in 0..nb) box(0.15 + k * (len - 0.3) / max(1, nb), 0.25, eave - 0.9, 0.07, 0.22, 0.2, cornice, ink = false, skip = Details.BACK or Details.TOP)
        val units = max(1, (len / 7.6).roundToInt())
        val uw = len / units
        if (uw < 4.5 || levels < 2) return 0
        val wood = 0x4A4D55
        val tread = 0x3A3D44
        var made = 0
        for (i in 0 until units) {
            val s0 = i * uw
            box(s0 + uw / 2, 0.1, eave + 0.55, 0.9, 0.16, 0.2, cornice)
            box(s0 + uw / 2, 0.1, eave + 0.9, 0.35, 0.16, 0.15, cornice)
            val mirror = (seed + i) % 2 == 1
            val bw = min(2.8, uw * 0.42)
            val sb0 = if (mirror) s0 + 0.1 else s0 + uw - bw - 0.1
            val sb1 = sb0 + bw
            val bd = 1.35 // balcony depth

            // The staircase to the 2nd floor: straight out, or along the façade.
            val hb = ground + floorH
            val steps = kotlin.math.ceil(floorH / 0.2).toInt()
            val rise = floorH / steps
            var perp = false
            var gap0 = 0.0; var gap1 = 0.0          // front rail opening (perpendicular stair)
            var stairFoot0 = 0.0; var stairFoot1 = 0.0 // yard span the stair takes
            val outEnd = bd + min(steps * 0.27, free - 0.8)
            if (free >= 4.0 && (outEnd - bd) / steps >= 0.18) {
                perp = true
                val run = (outEnd - bd) / steps
                val ss0 = if (mirror) sb0 + 0.05 else sb1 - 1.1
                val ss1 = ss0 + 1.05
                for (k in 0 until steps) {
                    val h = hb - (k + 1) * rise
                    box((ss0 + ss1) / 2, bd + (k + 0.5) * run, h + 0.03, 0.52, run / 2 + 0.02, 0.03, tread, ink = false, skip = treadSkip)
                }
                for (s in doubleArrayOf(ss0, ss1)) {
                    d.beam(px(s, bd), py(s, bd), hb - 0.1, px(s, outEnd), py(s, outEnd), ground - 0.05, 0.04, 0.12, tread)
                    rail(s, bd, hb, s, outEnd, ground + 0.15, 0.95)
                }
                gap0 = ss0; gap1 = ss1
                stairFoot0 = ss0 - 0.2; stairFoot1 = ss1 + 0.2
            } else if (free >= 2.4) {
                // Along the façade, under the balcony's inner edge, down towards the unit's middle.
                val sTop = if (mirror) sb1 else sb0
                val dir = if (mirror) 1.0 else -1.0
                val room = if (mirror) s0 + uw - 0.3 - sTop else sTop - s0 - 0.3
                val run = min(0.25, room / steps)
                if (run >= 0.18) {
                    val l = run * steps
                    for (k in 0 until steps) {
                        val h = hb - (k + 1) * rise
                        box(sTop + dir * (k + 0.5) * run, 0.8, h + 0.03, run / 2 + 0.02, 0.5, 0.03, tread, ink = false, skip = treadSkip)
                    }
                    d.beam(px(sTop, 1.3), py(sTop, 1.3), hb - 0.1, px(sTop + dir * l, 1.3), py(sTop + dir * l, 1.3), ground - 0.05, 0.04, 0.12, tread)
                    rail(sTop, 1.3, hb, sTop + dir * l, 1.3, ground + 0.15, 0.95)
                    stairFoot0 = min(sTop, sTop + dir * l) - 0.2; stairFoot1 = max(sTop, sTop + dir * l) + 0.2
                }
            }

            // Balconies on the upper floors, on brackets.
            for (fl in 1 until levels) {
                val h = ground + fl * floorH
                box((sb0 + sb1) / 2, bd / 2, h - 0.08, bw / 2, bd / 2, 0.08, wood)
                if (fl == 1 && perp) {
                    if (gap0 - sb0 > 0.1) rail(sb0, bd, h, gap0, bd, h)
                    if (sb1 - gap1 > 0.1) rail(gap1, bd, h, sb1, bd, h)
                } else rail(sb0, bd, h, sb1, bd, h)
                rail(sb0, 0.05, h, sb0, bd, h)
                rail(sb1, 0.05, h, sb1, bd, h)
                for (s in doubleArrayOf(sb0 + 0.12, sb1 - 0.12)) {
                    d.beam(px(s, 0.02), py(s, 0.02), h - 0.85, px(s, bd - 0.15), py(s, bd - 0.15), h - 0.12, 0.03, 0.03, Details.IRON)
                }
                made++
            }

            // The front yard: a low iron fence and a hedge, clear of the stair foot.
            if (free >= 2.0) {
                val fo = free - 0.35
                if (stairFoot1 > stairFoot0 && perp) {
                    if (stairFoot0 - s0 > 0.3) rail(s0, fo, ground, stairFoot0, fo, ground, 0.75)
                    if (s0 + uw - stairFoot1 > 0.3) rail(stairFoot1, fo, ground, s0 + uw, fo, ground, 0.75)
                } else rail(s0, fo, ground, s0 + uw, fo, ground, 0.75)
                val h0 = if (mirror) max(s0 + 0.2, stairFoot1) else s0 + 0.2
                val h1 = if (mirror) s0 + uw - 0.2 else if (stairFoot1 > stairFoot0) min(s0 + uw - 0.2, stairFoot0) else s0 + uw * 0.55
                if (h1 - h0 > 1.0 && free >= 2.6) box((h0 + h1) / 2, free - 0.9, ground + 0.45, (h1 - h0) / 2, 0.35, 0.47, 0x3F6B33, skip = Details.BOTTOM)
            }
        }
        return made
    }

    /** Dome / onion / cone / pyramid over any footprint (towers, church spires, landmarks). */
    private fun radialRoof(chunk: Chunk, ring: DoubleArray, n: Int, eave: Double, h: Double, rgb: Int, shape: String) {
        val c = Geometry.centroid(ring, n)
        val steps = if (shape == "cone" || shape == "pyramidal") 1 else 5
        fun ringAt(k: Int): Pair<Double, Double> { // (scale, height) for step k
            val f = k.toDouble() / steps
            return when (shape) {
                "dome", "round" -> kotlin.math.cos(f * PI / 2) to h * kotlin.math.sin(f * PI / 2)
                "onion" -> (if (f < 0.4) 1.0 + 0.35 * kotlin.math.sin(f / 0.4 * PI / 2) else 1.35 * (1 - (f - 0.4) / 0.6)) to h * f
                else -> (1 - f) to h * f
            }
        }
        val m = chunk.roofs
        for (k in 0 until steps) {
            val (s0, h0) = ringAt(k); val (s1, h1) = ringAt(k + 1)
            for (i in 0 until n) {
                val j = (i + 1) % n
                val pts = listOf(
                    doubleArrayOf(c[0] + (ring[2 * i] - c[0]) * s0, c[1] + (ring[2 * i + 1] - c[1]) * s0, eave + h0),
                    doubleArrayOf(c[0] + (ring[2 * j] - c[0]) * s0, c[1] + (ring[2 * j + 1] - c[1]) * s0, eave + h0),
                    doubleArrayOf(c[0] + (ring[2 * j] - c[0]) * s1, c[1] + (ring[2 * j + 1] - c[1]) * s1, eave + h1),
                    doubleArrayOf(c[0] + (ring[2 * i] - c[0]) * s1, c[1] + (ring[2 * i + 1] - c[1]) * s1, eave + h1),
                )
                // Face normal (Godot space), pointing up / outwards.
                val ux = pts[1][0] - pts[0][0]; val uy = pts[1][2] - pts[0][2]; val uz = -(pts[1][1] - pts[0][1])
                val vx = pts[3][0] - pts[0][0]; val vy = pts[3][2] - pts[0][2]; val vz = -(pts[3][1] - pts[0][1])
                var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9)
                nx /= l; ny /= l; nz /= l
                if (ny < 0) { nx = -nx; ny = -ny; nz = -nz }
                val s = m.vertexCount
                for (p in pts) m.v(p[0], p[2], -p[1], nx, ny, nz, rgb)
                m.tri(s, s + 1, s + 2); m.tri(s, s + 2, s + 3)
            }
        }
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

    /** Props (small) are gathered for all chunks up front: chunk -> (json, count). */
    private val propBuf = HashMap<Int, Pair<StringBuilder, Int>>()

    private fun prop(type: String, x0: Double, y0: Double, scale: Double, rot: Double, variant: Int = 0) {
        var x = x0; var y = y0
        // Nothing stands in the carriageway: signals / stops mapped on the street's
        // centre line go to the kerb, trees just behind it; bollards there are dropped.
        if (type != "car") {
            // (At a junction the kerb of one street is in the other: step out of each in turn.)
            for (step in 0 until 3) {
                val c = roadClearance(x, y, if (type in TREES) 1.0 else 0.5)
                if (c[0] >= 0.3) break
                if (type == "bollard") return
                x = c[1]; y = c[2]
            }
            if (roadClearance(x, y)[0] < 0.2) return // no room anywhere near
        }
        val near = nearest(x, y, PROP_RANGE) ?: return
        val idx = chunkIndex(near[0])
        val (sb, count) = propBuf[idx] ?: (StringBuilder() to 0)
        if (count > 0) sb.append(',')
        sb.append("{\"t\":\"$type\",\"p\":[${fmt(x)},${fmt(terrain.height(x, y))},${fmt(-y)}],\"s\":${fmt(scale)},\"r\":${fmt(rot)},\"k\":$variant}")
        propBuf[idx] = sb to count + 1
    }

    /**
     * The tree's silhouette: species from the map tags (genus / species / taxon,
     * Latin or English), else a typical street tree mix for the region.
     */
    private fun treeKind(t: Map<String, String>, k: Int, park: Boolean = false): String {
        if (t["leaf_type"] == "needleleaved") return "conifer"
        val name = listOfNotNull(t["genus"], t["species"], t["taxon"], t["genus:en"], t["species:en"], t["taxon:en"], t["species:fr"], t["genus:fr"])
            .joinToString(" ").lowercase()
        if (name.isNotEmpty()) {
            val kind = when {
                Regex("\\bacer\\b|maple|érable|erable|ahorn").containsMatchIn(name) -> "maple"
                Regex("tilia|linden|basswood|\\blime\\b|tilleul|linde").containsMatchIn(name) -> "linden"
                Regex("fraxinus|\\bash\\b|frêne|frene|esche").containsMatchIn(name) -> "ash"
                Regex("gleditsia|locust|robinia|févier|fevier").containsMatchIn(name) -> "locust"
                Regex("ulmus|\\belm\\b|celtis|hackberry|orme|micocoulier|platanus|plane|platane").containsMatchIn(name) -> "elm"
                Regex("quercus|\\boak\\b|chêne|chene|eiche").containsMatchIn(name) -> "oak"
                Regex("populus|poplar|aspen|cottonwood|peuplier|ginkgo|pappel").containsMatchIn(name) -> "poplar"
                Regex("betula|birch|bouleau|birke").containsMatchIn(name) -> "birch"
                Regex("pinus|picea|abies|thuja|juniperus|larix|pine|spruce|\\bfir\\b|cedar|pin |épinette|sapin|cèdre").containsMatchIn(name) -> "conifer"
                Regex("malus|prunus|syringa|pyrus|amelanchier|crabapple|cherry|lilac|pommier|cerisier|lilas|sorbus").containsMatchIn(name) -> "small_tree"
                else -> null
            }
            if (kind != null) return kind
        }
        val r = k and 15
        return when (region) {
            // Montreal streets: silver / Norway maples first, then lindens, honey locusts, ash, hackberry.
            Region.QUEBEC -> when {
                r < 7 -> "maple"; r < 9 -> "linden"; r < 11 -> "locust"; r < 13 -> "ash"; r < 15 -> "elm"
                else -> if (park) "oak" else "small_tree"
            }
            Region.NORTH_AMERICA -> when { r < 6 -> "maple"; r < 9 -> "oak"; r < 11 -> "linden"; r < 13 -> "locust"; else -> "elm" }
            Region.EUROPE -> when { r < 6 -> "linden"; r < 9 -> "maple"; r < 11 -> "elm"; r < 13 -> "ash"; r < 14 -> "oak"; else -> "birch" }
            Region.OTHER -> "tree"
        }
    }

    /**
     * Cars parked along the kerbs of residential and local streets near the route:
     * every ~6 m (with gaps), clear of junctions, both sides on wide streets.
     */
    private fun parkedCars() {
        // Kerbside parking: local streets; in North America the bigger streets too.
        val wide = region == Region.QUEBEC || region == Region.NORTH_AMERICA
        val eligible = if (wide) setOf("residential", "unclassified", "tertiary", "secondary") else setOf("residential", "unclassified")
        val roadWays = osm.ways.filter { it.tags["highway"] != null }
        fun vkey(x: Double, y: Double) = (Math.round(x * 2) shl 32) or (Math.round(y * 2) and 0xFFFFFFFFL)
        val uses = HashMap<Long, Int>()
        for (w in roadWays) {
            val hw = w.tags["highway"]!!
            if (hw in setOf("footway", "path", "steps", "cycleway", "track", "pedestrian", "bridleway", "corridor")) continue
            for (i in 0 until w.xy.size / 2) uses.merge(vkey(w.xy[2 * i], w.xy[2 * i + 1]), 1, Int::plus)
        }
        for (w in roadWays) {
            val t = w.tags
            val hw = t["highway"]!!
            if (hw !in eligible) continue
            if (t["parking:both"] in setOf("no", "no_parking") || t["parking:lane:both"] in setOf("no", "no_parking", "no_stopping")) continue
            if (t["bridge"] == "yes" || t["tunnel"] == "yes") continue
            val width = carriageway(t) ?: continue
            val sides = if (width >= 8.0) intArrayOf(1, -1) else intArrayOf(if (w.id % 2 == 0L) 1 else -1)
            val n = w.xy.size / 2
            if (n < 2) continue
            // Distance along the way of every junction vertex.
            val cum = DoubleArray(n)
            for (i in 1 until n) cum[i] = cum[i - 1] + hypot(w.xy[2 * i] - w.xy[2 * i - 2], w.xy[2 * i + 1] - w.xy[2 * i - 1])
            val junctions = (0 until n).filter { (uses[vkey(w.xy[2 * it], w.xy[2 * it + 1])] ?: 0) >= 2 }.map { cum[it] }
            var seg = 0
            var s = 4.0
            while (s < cum[n - 1] - 4.0) {
                while (seg < n - 2 && cum[seg + 1] < s) seg++
                if (junctions.none { abs(it - s) < 12.0 }) {
                    val ax = w.xy[2 * seg]; val ay = w.xy[2 * seg + 1]; val bx = w.xy[2 * seg + 2]; val by = w.xy[2 * seg + 3]
                    val l = (cum[seg + 1] - cum[seg]).coerceAtLeast(1e-6)
                    val f = (s - cum[seg]) / l
                    val x = ax + (bx - ax) * f; val y = ay + (by - ay) * f
                    val dx = (bx - ax) / l; val dy = (by - ay) / l
                    // Only near the route (and not on it).
                    val nr = nearest(x, y, PROP_RANGE)
                    if (nr != null) for (side in sides) {
                        val h = (Math.round(x * 3.1) * 73856093L) xor (Math.round(y * 2.7) * 19349663L) xor side.toLong()
                        if (((h ushr 8) and 0xFF) > 95) continue // ~3 in 8 spots taken
                        val off = side * (width / 2 - 1.1)
                        val cx = x + dy * off; val cy = y - dx * off
                        // None on the route's own street: the rider's line stays clear.
                        if (routeDistance(cx, cy) < 8.0) continue
                        prop("car", cx, cy, 1.0, kotlin.math.atan2(dx, -dy) + if ((h and 1L) == 0L) PI else 0.0, ((h ushr 16) and 7).toInt())
                    }
                }
                s += 6.2
            }
        }
    }

    private fun propsByChunk(): Map<Int, Pair<StringBuilder, Int>> {
        buildProps()
        return propBuf
    }

    private fun buildProps() {
        parkedCars()
        val treePts = ArrayList<DoubleArray>()
        for (p in osm.points) {
            val t = p.tags
            val rot = ((p.id * 2654435761L) and 0xFFFF) / 65535.0 * 2 * PI
            val v = ((p.id ushr 3) and 7).toInt()
            when {
                t["natural"] == "tree" -> {
                    val h = parseMeters(t["height"]) ?: (8.0 + v)
                    prop(treeKind(t, (p.id ushr 5).toInt()), p.x, p.y, h / 10.0, rot, v)
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
                    prop(treeKind(w.tags, k ushr 3), x, y, 0.9 + (k and 3) * 0.08, (k and 63) / 10.0, k and 7)
                    treePts.add(doubleArrayOf(x, y))
                    s += 9.0
                }
                carry = s - len
            }
        }
        // Woods and parks without mapped trees: scatter. Cheap checks first (near the
        // route? one lookup), then grid-indexed lookups of nearby buildings / trees.
        val bGrid = HashMap<Long, MutableList<Pair<DoubleArray, DoubleArray>>>()
        for (b in osm.areas) {
            if (!b.tags.containsKey("building")) continue
            val bb = Geometry.bbox(b.outers)
            if (nearest((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2, PROP_RANGE + 200) == null) continue
            for (i in floor(bb[0] / 50).toLong()..floor(bb[2] / 50).toLong()) for (j in floor(bb[1] / 50).toLong()..floor(bb[3] / 50).toLong()) {
                bGrid.getOrPut((i shl 32) or (j and 0xFFFFFFFFL)) { ArrayList() }.add(b.outers[0] to bb)
            }
        }
        val tGrid = HashMap<Long, MutableList<DoubleArray>>()
        for (p in treePts) tGrid.getOrPut((floor(p[0] / 50).toLong() shl 32) or (floor(p[1] / 50).toLong() and 0xFFFFFFFFL)) { ArrayList() }.add(p)
        fun inBuilding(x: Double, y: Double): Boolean =
            bGrid[(floor(x / 50).toLong() shl 32) or (floor(y / 50).toLong() and 0xFFFFFFFFL)]
                ?.any { (r, b) -> x in b[0]..b[2] && y in b[1]..b[3] && Geometry.contains(r, x, y) } ?: false
        // Street trees seen in the photos, where the map has none close by (and not
        // on the route itself).
        for (p in photoTrees) {
            val x = p[0]; val y = p[1]
            if (nearest(x, y, PROP_RANGE) == null || routeDistance(x, y) < 4.0 || inBuilding(x, y)) continue
            // A photo guess is only a guess: never on (or right next to) a street.
            if (roadClearance(x, y)[0] < 1.0) continue
            val near = tGrid[(floor(x / 50).toLong() shl 32) or (floor(y / 50).toLong() and 0xFFFFFFFFL)]
                ?.any { hypot(it[0] - x, it[1] - y) < 6.0 } ?: false
            if (near) continue
            val k = (x * 7 + y * 13).toInt()
            prop(treeKind(emptyMap(), k ushr 3), x, y, 0.85 + (k and 3) * 0.08, (k and 63) / 10.0, k and 7)
        }
        for (a in osm.areas) {
            val t = a.tags
            val dense = t["natural"] == "wood" || t["landuse"] == "forest"
            val sparse = t["leisure"] in setOf("park", "garden") || t["landuse"] in setOf("cemetery", "grass", "village_green")
            if (!dense && !sparse) continue
            val ring = a.outers.maxByOrNull { abs(Geometry.area(it)) } ?: continue
            val full = Geometry.bbox(listOf(ring))
            val step = if (dense) 6.0 else 13.0
            // Skip parks whose own trees are mapped (Berlin maps its park trees).
            if (!dense) {
                var mapped = false
                loop@ for (i in floor(full[0] / 50).toLong()..floor(full[2] / 50).toLong()) for (j in floor(full[1] / 50).toLong()..floor(full[3] / 50).toLong()) {
                    val list = tGrid[(i shl 32) or (j and 0xFFFFFFFFL)] ?: continue
                    for (p in list) if (Geometry.contains(ring, p[0], p[1])) { mapped = true; break@loop }
                }
                if (mapped) continue
            }
            // Visit only the corridor cells overlapping this area, then the tree grid inside them.
            for (ci in floor(full[0] / CELL).toLong()..floor(full[2] / CELL).toLong()) {
                for (cj in floor(full[1] / CELL).toLong()..floor(full[3] / CELL).toLong()) {
                    val e = field[(ci shl 32) or (cj and 0xFFFFFFFFL)] ?: continue
                    if (e[1] > PROP_RANGE + CELL) continue
                    var y = kotlin.math.ceil(cj * CELL / step) * step
                    while (y < (cj + 1) * CELL) {
                        var x = kotlin.math.ceil(ci * CELL / step) * step
                        while (x < (ci + 1) * CELL) {
                            val hx = ((x * 73856093).toLong() xor (y * 19349663).toLong())
                            val jx = x + ((hx and 0xFF) / 255.0) * step
                            val jy = y + (((hx shr 8) and 0xFF) / 255.0) * step
                            val keep = ((hx shr 16) and 0xFF) / 255.0 < (if (dense) 0.85 else 0.35)
                            if (keep && nearest(jx, jy, PROP_RANGE) != null && Geometry.contains(ring, jx, jy) &&
                                a.inners.none { Geometry.contains(it, jx, jy) } && !inBuilding(jx, jy)
                            ) {
                                val conifer = dense && ((hx shr 24) and 3) == 0L
                                prop(if (conifer) "conifer" else treeKind(emptyMap(), (hx ushr 20).toInt() and 0xFFFF, park = true), jx, jy, 0.8 + ((hx shr 26) and 7) / 14.0, ((hx shr 4) and 63) / 10.0, ((hx shr 12) and 7).toInt())
                            }
                            x += step
                        }
                        y += step
                    }
                }
            }
        }
    }
}
