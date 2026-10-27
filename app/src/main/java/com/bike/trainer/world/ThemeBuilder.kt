package com.bike.trainer.world

import com.bike.trainer.route.Route
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A video-game themed world around a route: the route keeps its real shape, length
 * and climbs (it is ridden on a raised track at the real elevation), but everything
 * around it is generated in a game's style instead of being read from the map.
 * Needs no map data, only the elevation model, so it builds in seconds.
 *
 * Each theme is a [Kit] with its own tiles, stages, track and scenery:
 *  - "robot" (Robot City): an 8-bit action-game world of pixel-art blocks, inspired
 *    by the classic robot platformers. Stages: sky bridge, factory, forest, ice,
 *    night fortress, each opened by a boss shutter gate.
 *  - "quest" (Kingdom Quest): a 16-bit adventure-game world, inspired by the classic
 *    top-down fantasy adventures. A dirt road (wooden bridges over drops) through
 *    meadows, woods, a village, a lake, a mountain and a castle, with round trees,
 *    bushes, rocks, pots, chests and gems.
 *
 * Output matches [SceneBuilder] (chunk_NNN.glb / .json, world.json) so the same
 * Godot scene streams it; world.json adds "theme" and the stage list. Meshes use a
 * tile atlas (res://themes/<theme>_atlas.png): UV0 repeats the tile, UV1.x picks it.
 */
class ThemeBuilder(private val route: Route, private val dem: HeightSource, private val theme: String = "robot") {

    /** A game theme: stages, the track and what stands around it. */
    private abstract inner class Kit {
        abstract val stages: List<String>
        /** Track half width. */
        abstract val half: Double
        /** Height of the terrain block in cell (i, j) (null = the track runs there). */
        abstract fun cellTop(i: Long, j: Long, e: CellInfo): Double?
        /** Top and side tiles of a terrain block. */
        abstract fun cellTiles(i: Long, j: Long, e: CellInfo, t: Double): IntArray
        abstract fun track(d0: Double, d1: Double)
        abstract fun structures(i: Long, j: Long, e: CellInfo, t: Double)
        abstract fun features(d0: Double, d1: Double)
    }

    private val kit: Kit = if (theme == "quest") QuestKit() else RobotKit()

    private fun stageIndex(d: Double) = ((d / stageLen()).toInt()) % kit.stages.size

    /** The stages of Robot City, in riding order (they repeat on long routes). */
    enum class Stage(val key: String, val top: Int, val side: Int, val towerSide: Int, val towerTop: Int, val track: Int) {
        SKY("sky", Tile.METAL_BLOCK, Tile.BRICK_BLUE, Tile.WINDOWS, Tile.METAL_BLOCK, Tile.TRACK),
        FACTORY("factory", Tile.METAL, Tile.BRICK_GREY, Tile.CAUTION, Tile.METAL, Tile.TRACK),
        FOREST("forest", Tile.GRASS, Tile.DIRT, Tile.WOOD, Tile.GRASS, Tile.TRACK),
        ICE("ice", Tile.SNOW, Tile.ICE, Tile.ICE, Tile.SNOW, Tile.TRACK),
        NIGHT("night", Tile.CIRCUIT, Tile.DARK_METAL, Tile.WINDOWS, Tile.DARK_METAL, Tile.TRACK),
    }

    /** Atlas tile indices (see the atlas generator: row * 8 + column). */
    object Tile {
        const val METAL = 0; const val HAZARD = 1; const val GIRDER = 2; const val BRICK_BLUE = 3; const val BRICK_GREY = 4
        const val METAL_BLOCK = 5; const val WINDOWS = 6; const val PIPE = 7; const val LADDER = 8; const val SPIKES = 9
        const val CLOUD = 10; const val GRASS = 11; const val DIRT = 12; const val WOOD = 13; const val LEAVES = 14
        const val SHUTTER = 15; const val CIRCUIT = 16; const val ICE = 17; const val SNOW = 18; const val CAUTION = 19
        const val PILLAR = 20; const val TRACK = 21; const val DARK_METAL = 22; const val LAVA = 23
    }

    companion object {
        const val VERSION = 2
        const val CHUNK_LEN = 400.0
        private const val STAGE_LEN = 1600.0
        private const val CELL = 5.0
        private const val TILE_M = 2.5      // one 16 px tile covers 2.5 m
        private const val HALF = 3.6        // track half width
        private const val RANGE = 120.0     // blocks this far from the track
        private const val STEP = 1.0        // terrain height steps
    }

    // Route samples every 5 m: [d, x, y, track height].
    private val rs = ArrayList<DoubleArray>()

    init {
        var d = 0.0
        val raw = ArrayList<DoubleArray>()
        while (true) {
            val p = route.pointAt(d)
            raw.add(doubleArrayOf(d, p.x, p.y, dem.height(p.x, p.y)))
            if (d >= route.totalDistance) break
            d = min(d + 5.0, route.totalDistance)
        }
        // A smooth track: average the elevation over ±25 m (keeps the real climbs).
        for (i in raw.indices) {
            var s = 0.0; var n = 0
            for (k in max(0, i - 5)..min(raw.size - 1, i + 5)) { s += raw[k][3]; n++ }
            rs.add(doubleArrayOf(raw[i][0], raw[i][1], raw[i][2], s / n))
        }
    }

    private fun stageAt(d: Double): Stage = Stage.entries[stageIndex(d) % Stage.entries.size]

    private fun stageLen() = if (route.totalDistance < STAGE_LEN * 2) max(400.0, route.totalDistance / 3) else STAGE_LEN

    private fun hash(i: Long, j: Long, salt: Int = 0): Int {
        var h = i * 73856093L xor j * 19349663L xor salt * 83492791L
        h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
        return (h and 0x7FFFFFFF).toInt()
    }

    private fun vnoise(x: Double, y: Double): Double {
        val i = floor(x).toLong(); val j = floor(y).toLong()
        val fx = x - i; val fy = y - j
        fun r(a: Long, b: Long) = (hash(a, b, 7) % 1000) / 1000.0
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        return (r(i, j) * (1 - ux) + r(i + 1, j) * ux) * (1 - uy) + (r(i, j + 1) * (1 - ux) + r(i + 1, j + 1) * ux) * uy
    }

    // ---------------------------------------------------------------------------------

    private inner class Chunk {
        val mesh = MeshData()
        val props = StringBuilder()
        var propCount = 0
    }

    private lateinit var cur: Chunk

    /** Build everything; [write] receives each output file (name, bytes). */
    fun build(write: (String, ByteArray) -> Unit, progress: (String) -> Unit = {}) {
        val n = max(1, ceil(route.totalDistance / CHUNK_LEN).toInt())
        val chunks = StringBuilder()
        for (c in 0 until n) {
            progress("Chunk ${c + 1}/$n")
            cur = Chunk()
            val d0 = c * CHUNK_LEN; val d1 = min(route.totalDistance, (c + 1) * CHUNK_LEN)
            terrain(d0, d1)
            kit.track(d0, d1)
            kit.features(d0, d1)
            val glb = GlbWriter()
            glb.add("tiles", cur.mesh, "tiles")
            val name = "chunk_%03d".format(c)
            if (!glb.isEmpty()) write("$name.glb", glb.bytes())
            write("$name.json", "{\"edges\":[],\"props\":[${cur.props}]}".toByteArray())
            if (c > 0) chunks.append(',')
            chunks.append("{\"i\":$c,\"d0\":${fmt(d0)},\"d1\":${fmt(c * CHUNK_LEN + CHUNK_LEN)}}")
        }
        val sb = StringBuilder("{\"version\":${SceneBuilder.VERSION},\"theme\":\"$theme\",\"name\":\"${route.name.replace("\"", "'")}\",")
        sb.append("\"length\":${fmt(route.totalDistance)},\"chunkLength\":${fmt(CHUNK_LEN)},\"route\":[")
        rs.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append(fmt(s[1])).append(',').append(fmt(s[3])).append(',').append(fmt(-s[2]))
        }
        sb.append("],\"routeStep\":5,\"stages\":[")
        var d = 0.0; var first = true
        while (d < route.totalDistance) {
            if (!first) sb.append(',')
            sb.append("{\"d\":${fmt(d)},\"s\":\"${kit.stages[stageIndex(d)]}\"}")
            first = false
            d += stageLen()
        }
        sb.append("],\"chunks\":[").append(chunks).append("]}")
        write("world.json", sb.toString().toByteArray())
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }

    private fun idx(d: Double) = (d / 5.0).toInt().coerceIn(0, rs.size - 1)

    // ---------------------------------------------------------------------------------
    // Geometry helpers (x east, y north, h up → Godot x, h, -y)

    private fun quad(ax: Double, ay: Double, ah: Double, bx: Double, by: Double, bh: Double,
                     cx: Double, cy: Double, ch: Double, dx: Double, dy: Double, dh: Double,
                     tile: Int, u0: Double, v0: Double, u1: Double, v1: Double, rgb: Int = 0xFFFFFF) {
        // Normal from the first three corners (Godot space).
        val e1x = bx - ax; val e1y = bh - ah; val e1z = -(by - ay)
        val e2x = dx - ax; val e2y = dh - ah; val e2z = -(dy - ay)
        var nx = e1y * e2z - e1z * e2y; var ny = e1z * e2x - e1x * e2z; var nz = e1x * e2y - e1y * e2x
        val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9)
        nx /= l; ny /= l; nz /= l
        if (ny < -0.5) { nx = -nx; ny = -ny; nz = -nz } // floors face up (walls are lit from either side)
        val m = cur.mesh
        val s = m.vertexCount
        val t = tile.toDouble()
        m.v(ax, ah, -ay, nx, ny, nz, rgb, u0, v1, t, 0.0)
        m.v(bx, bh, -by, nx, ny, nz, rgb, u1, v1, t, 0.0)
        m.v(cx, ch, -cy, nx, ny, nz, rgb, u1, v0, t, 0.0)
        m.v(dx, dh, -dy, nx, ny, nz, rgb, u0, v0, t, 0.0)
        m.tri(s, s + 1, s + 2); m.tri(s, s + 2, s + 3)
    }

    /** A box around (x, y), axes along (ux, uy) / its left normal, from h0 to h1. */
    private fun box(x: Double, y: Double, ux: Double, uy: Double, halfA: Double, halfB: Double, h0: Double, h1: Double,
                    top: Int, side: Int, rgb: Int = 0xFFFFFF, bottom: Boolean = false) {
        val vx = -uy; val vy = ux
        fun cx(a: Double, b: Double) = x + ux * a + vx * b
        fun cy(a: Double, b: Double) = y + uy * a + vy * b
        val p = arrayOf(doubleArrayOf(-halfA, -halfB), doubleArrayOf(halfA, -halfB), doubleArrayOf(halfA, halfB), doubleArrayOf(-halfA, halfB))
        val hTiles = (h1 - h0) / TILE_M
        for (k in 0 until 4) {
            val a = p[k]; val b = p[(k + 1) % 4]
            val len = hypot((b[0] - a[0]), (b[1] - a[1])) / TILE_M
            quad(cx(a[0], a[1]), cy(a[0], a[1]), h0, cx(b[0], b[1]), cy(b[0], b[1]), h0,
                cx(b[0], b[1]), cy(b[0], b[1]), h1, cx(a[0], a[1]), cy(a[0], a[1]), h1, side, 0.0, 0.0, len, hTiles, rgb)
        }
        val ta = 2 * halfA / TILE_M; val tb = 2 * halfB / TILE_M
        quad(cx(-halfA, -halfB), cy(-halfA, -halfB), h1, cx(halfA, -halfB), cy(halfA, -halfB), h1,
            cx(halfA, halfB), cy(halfA, halfB), h1, cx(-halfA, halfB), cy(-halfA, halfB), h1, top, 0.0, 0.0, ta, tb, rgb)
        if (bottom) quad(cx(-halfA, halfB), cy(-halfA, halfB), h0, cx(halfA, halfB), cy(halfA, halfB), h0,
            cx(halfA, -halfB), cy(halfA, -halfB), h0, cx(-halfA, -halfB), cy(-halfA, -halfB), h0, side, 0.0, 0.0, ta, tb, rgb)
    }

    private fun prop(type: String, x: Double, y: Double, h: Double, rot: Double, variant: Int, scale: Double = 1.0) {
        if (cur.propCount > 0) cur.props.append(',')
        cur.props.append("{\"t\":\"$type\",\"p\":[${fmt(x)},${fmt(h)},${fmt(-y)}],\"s\":${fmt(scale)},\"r\":${fmt(rot)},\"k\":$variant}")
        cur.propCount++
    }

    // ---------------------------------------------------------------------------------
    // The track: a raised platform at the route's real elevation.

    private fun dirAt(i: Int): DoubleArray {
        val a = rs[max(0, i - 1)]; val b = rs[min(rs.size - 1, i + 1)]
        val dx = b[1] - a[1]; val dy = b[2] - a[2]
        val l = hypot(dx, dy).coerceAtLeast(1e-6)
        return doubleArrayOf(dx / l, dy / l)
    }

    private fun groundAt(x: Double, y: Double) = floor(dem.height(x, y) / STEP) * STEP

    private fun robotTrack(d0: Double, d1: Double) {
        val i0 = idx(d0); val i1 = min(rs.size - 1, idx(d1) + 1)
        for (i in i0 until i1) {
            val a = rs[i]; val b = rs[i + 1]
            val da = dirAt(i); val db = dirAt(i + 1)
            val stage = stageAt(a[0])
            // left normals
            val nax = -da[1]; val nay = da[0]; val nbx = -db[1]; val nby = db[0]
            val ha = a[3]; val hb = b[3]
            val v0 = a[0] / (2 * HALF); val v1 = b[0] / (2 * HALF)
            // Deck: one tile across the full width (centre dashes in the middle).
            quad(a[1] - nax * HALF, a[2] - nay * HALF, ha, a[1] + nax * HALF, a[2] + nay * HALF, ha,
                b[1] + nbx * HALF, b[2] + nby * HALF, hb, b[1] - nbx * HALF, b[2] - nby * HALF, hb, stage.track, 0.0, v1, 1.0, v0)
            for (side in intArrayOf(-1, 1)) {
                val o0 = side * HALF
                val o1 = side * (HALF + 0.45)
                fun ax(o: Double) = a[1] + nax * o
                fun ay(o: Double) = a[2] + nay * o
                fun bx(o: Double) = b[1] + nbx * o
                fun by(o: Double) = b[2] + nby * o
                val c = 0.3
                val tv0 = a[0] / TILE_M; val tv1 = b[0] / TILE_M
                // Hazard curb: top and inner face.
                if (side > 0) {
                    quad(ax(o0), ay(o0), ha + c, bx(o0), by(o0), hb + c, bx(o1), by(o1), hb + c, ax(o1), ay(o1), ha + c, Tile.HAZARD, tv0, 0.0, tv1, 0.18)
                    quad(ax(o0), ay(o0), ha, bx(o0), by(o0), hb, bx(o0), by(o0), hb + c, ax(o0), ay(o0), ha + c, Tile.HAZARD, tv0, 0.0, tv1, 0.12)
                } else {
                    quad(ax(o1), ay(o1), ha + c, bx(o1), by(o1), hb + c, bx(o0), by(o0), hb + c, ax(o0), ay(o0), ha + c, Tile.HAZARD, tv0, 0.0, tv1, 0.18)
                    quad(bx(o0), by(o0), hb, ax(o0), ay(o0), ha, ax(o0), ay(o0), ha + c, bx(o0), by(o0), hb + c, Tile.HAZARD, tv1, 0.0, tv0, 0.12)
                }
                // Girder side down to the ground (or well below on bridges).
                val ga = min(ha - 1.2, groundAt(ax(o1), ay(o1)) - 0.5)
                val gb = min(hb - 1.2, groundAt(bx(o1), by(o1)) - 0.5)
                if (side > 0) quad(ax(o1), ay(o1), ga, bx(o1), by(o1), gb, bx(o1), by(o1), hb + c, ax(o1), ay(o1), ha + c, Tile.GIRDER, tv0, 0.0, tv1, (ha + c - ga) / TILE_M)
                else quad(bx(o1), by(o1), gb, ax(o1), ay(o1), ga, ax(o1), ay(o1), ha + c, bx(o1), by(o1), hb + c, Tile.GIRDER, tv1, 0.0, tv0, (ha + c - ga) / TILE_M)
            }
            // Support pillars where the track is high above the ground.
            if (i % 4 == 0) {
                val g = groundAt(a[1], a[2])
                if (ha - g > 2.5) box(a[1], a[2], da[0], da[1], 0.7, 0.7, g - 0.5, ha - 0.1, Tile.PILLAR, Tile.PILLAR)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Blocky terrain around the track.

    private class CellInfo(val dist: Double, val d: Double, val trackH: Double)

    private inner class RobotKit : Kit() {
        override val stages = Stage.entries.map { it.key }
        override val half = HALF
        override fun cellTop(i: Long, j: Long, e: CellInfo) = robotCellTop(i, j, e)
        override fun cellTiles(i: Long, j: Long, e: CellInfo, t: Double): IntArray {
            val stage = stageAt(e.d)
            val lava = stage == Stage.NIGHT && e.dist > 12 && vnoise(i * 0.21, j * 0.21) < 0.28
            val sea = stage == Stage.SKY && e.dist > 16
            return intArrayOf(when { lava -> Tile.LAVA; sea -> Tile.CLOUD; else -> stage.top }, if (sea) Tile.CLOUD else stage.side)
        }
        override fun track(d0: Double, d1: Double) = robotTrack(d0, d1)
        override fun structures(i: Long, j: Long, e: CellInfo, t: Double) = robotStructures(i, j, e, t, stageAt(e.d))
        override fun features(d0: Double, d1: Double) = robotFeatures(d0, d1)
    }

    private fun terrain(d0: Double, d1: Double) {
        // Nearest route point for every cell around this stretch.
        val i0 = idx(d0 - RANGE - 40); val i1 = idx(d1 + RANGE + 40)
        val cells = HashMap<Long, CellInfo>()
        val reach = (RANGE / CELL).toInt() + 2
        for (k in i0..i1) {
            val s = rs[k]
            val ci = floor(s[1] / CELL).toLong(); val cj = floor(s[2] / CELL).toLong()
            for (i in ci - reach..ci + reach) for (j in cj - reach..cj + reach) {
                val x = (i + 0.5) * CELL; val y = (j + 0.5) * CELL
                val dd = hypot(x - s[1], y - s[2])
                if (dd > RANGE + CELL) continue
                val key = (i shl 32) or (j and 0xFFFFFFFFL)
                val e = cells[key]
                if (e == null || dd < e.dist) cells[key] = CellInfo(dd, s[0], s[3])
            }
        }
        fun top(i: Long, j: Long): Double? {
            val e = cells[(i shl 32) or (j and 0xFFFFFFFFL)] ?: return null
            return kit.cellTop(i, j, e)
        }
        for ((key, e) in cells) {
            if (e.d < d0 || e.d >= d1 || e.dist > RANGE) continue
            val i = key shr 32; val j = (key and 0xFFFFFFFFL).toInt().toLong()
            val t = kit.cellTop(i, j, e) ?: continue
            val x0 = i * CELL; val y0 = j * CELL; val x1 = x0 + CELL; val y1 = y0 + CELL
            val tiles = kit.cellTiles(i, j, e, t)
            val topTile = tiles[0]
            val n = CELL / TILE_M
            quad(x0, y0, t, x1, y0, t, x1, y1, t, x0, y1, t, topTile, (i * n).toDouble() % 64, (j * n).toDouble() % 64, (i * n).toDouble() % 64 + n, (j * n).toDouble() % 64 + n)
            // Sides where a neighbour is lower (or missing).
            val nbrs = arrayOf(longArrayOf(i, j - 1), longArrayOf(i + 1, j), longArrayOf(i, j + 1), longArrayOf(i - 1, j))
            for ((k, nb) in nbrs.withIndex()) {
                val nt = top(nb[0], nb[1]) ?: if (cells.containsKey((nb[0] shl 32) or (nb[1] and 0xFFFFFFFFL))) min(t, e.trackH - 1.0) else t - 6.0
                if (nt >= t - 0.01) continue
                val sideTile = tiles[1]
                val vh = (t - nt) / TILE_M
                when (k) {
                    0 -> quad(x0, y0, nt, x1, y0, nt, x1, y0, t, x0, y0, t, sideTile, 0.0, 0.0, n, vh)
                    1 -> quad(x1, y0, nt, x1, y1, nt, x1, y1, t, x1, y0, t, sideTile, 0.0, 0.0, n, vh)
                    2 -> quad(x1, y1, nt, x0, y1, nt, x0, y1, t, x1, y1, t, sideTile, 0.0, 0.0, n, vh)
                    else -> quad(x0, y1, nt, x0, y0, nt, x0, y0, t, x0, y1, t, sideTile, 0.0, 0.0, n, vh)
                }
            }
            kit.structures(i, j, e, t)
        }
    }

    /** Height of a terrain block (null = none: the track runs there). */
    private fun robotCellTop(i: Long, j: Long, e: CellInfo): Double? {
        if (e.dist < HALF + 1.5) return null
        val stage = stageAt(e.d)
        val x = (i + 0.5) * CELL; val y = (j + 0.5) * CELL
        var t = floor(dem.height(x, y) / STEP) * STEP
        if (stage == Stage.SKY && e.dist > 16) {
            // The sky bridge: a sea of clouds far below.
            return floor((e.trackH - 22 + vnoise(i * 0.15, j * 0.15) * 4) / STEP) * STEP
        }
        if (e.dist > 12) t += floor(vnoise(i * 0.13, j * 0.13) * 4 * min(1.0, (e.dist - 12) / 15)) * STEP
        if (stage == Stage.NIGHT && e.dist > 12 && vnoise(i * 0.21, j * 0.21) < 0.28) t -= 1.0
        // Next to the track the blocks stay below the deck.
        if (e.dist < 10) t = min(t, floor((e.trackH - 0.4) / STEP) * STEP)
        return t
    }

    /** Stage scenery standing on a block. */
    private fun robotStructures(i: Long, j: Long, e: CellInfo, t: Double, stage: Stage) {
        if (e.dist < 9) return
        val h = hash(i, j, 11)
        val r = h % 1000
        val x = (i + 0.5) * CELL; val y = (j + 0.5) * CELL
        when (stage) {
            Stage.SKY -> {
                // Floating cloud platforms near the bridge, block towers further out.
                if (e.dist in 9.0..34.0 && r < 22) {
                    val fh = e.trackH + 3 + (h / 1000 % 12)
                    box(x, y, 1.0, 0.0, 2.5, 2.5, fh, fh + 1.5, Tile.CLOUD, Tile.CLOUD, bottom = true)
                } else if (e.dist > 30 && r < 35) {
                    val th = e.trackH + 6 + (h / 1000 % 6) * 5.0
                    tower(x, y, t, th, stage, h)
                }
            }
            Stage.FACTORY -> {
                if (e.dist > 14 && r < 40) tower(x, y, t, t + 6 + (h / 1000 % 5) * 4.0, stage, h)
                else if (e.dist > 20 && r < 52) {
                    // Smokestack.
                    box(x, y, 1.0, 0.0, 1.6, 1.6, t, t + 22 + (h / 1000 % 3) * 6.0, Tile.DARK_METAL, Tile.BRICK_GREY)
                }
            }
            Stage.FOREST -> {
                if (r < 70 && e.dist > 9) {
                    val th = 5.0 + (h / 1000 % 4) * 1.5
                    box(x, y, 1.0, 0.0, 0.7, 0.7, t, t + th, Tile.WOOD, Tile.WOOD)
                    box(x, y, 1.0, 0.0, 2.6, 2.6, t + th - 1, t + th + 2.5, Tile.LEAVES, Tile.LEAVES, bottom = true)
                    box(x, y, 1.0, 0.0, 1.5, 1.5, t + th + 2.5, t + th + 4.0, Tile.LEAVES, Tile.LEAVES)
                } else if (e.dist > 40 && r < 90) tower(x, y, t, t + 10 + (h / 1000 % 4) * 4.0, stage, h)
            }
            Stage.ICE -> {
                if (r < 45 && e.dist > 10) {
                    val th = 3.0 + (h / 1000 % 6) * 2.0
                    box(x, y, 1.0, 0.0, 1.2, 1.2, t, t + th, Tile.SNOW, Tile.ICE)
                    box(x, y, 1.0, 0.0, 0.6, 0.6, t + th, t + th + 2, Tile.ICE, Tile.ICE)
                } else if (e.dist > 35 && r < 60) tower(x, y, t, t + 8 + (h / 1000 % 4) * 4.0, stage, h)
            }
            Stage.NIGHT -> {
                if (e.dist > 18 && r < 45) tower(x, y, t, t + 10 + (h / 1000 % 6) * 5.0, stage, h)
            }
        }
    }

    /** A block tower: windowed / plated sides, sometimes a narrower top tier and a ladder. */
    private fun tower(x: Double, y: Double, base: Double, top: Double, stage: Stage, h: Int) {
        val side = if ((h shr 3) % 3 == 0) stage.side else stage.towerSide
        box(x, y, 1.0, 0.0, 2.5, 2.5, base, top, stage.towerTop, side)
        if ((h shr 5) % 2 == 0) box(x, y, 1.0, 0.0, 1.5, 1.5, top, top + 3 + (h shr 7) % 3 * 2.0, stage.towerTop, stage.side)
        if ((h shr 9) % 3 == 0) {
            // A ladder up one face.
            quad(x - 0.6, y - 2.52, base, x + 0.6, y - 2.52, base, x + 0.6, y - 2.52, top, x - 0.6, y - 2.52, top,
                Tile.LADDER, 0.0, 0.0, 1.0, (top - base) / TILE_M)
        }
    }

    // ---------------------------------------------------------------------------------
    // Along the track: gates, pipe arches, spikes, robots and energy capsules.

    private fun robotFeatures(d0: Double, d1: Double) {
        // Boss shutter gates where a stage starts (and at the start line).
        var g = 0.0
        while (g < route.totalDistance) {
            if (g >= d0 && g < d1) gate(g + 10.0)
            g += stageLen()
        }
        var d = (floor(d0 / 10) * 10).coerceAtLeast(20.0)
        while (d < d1) {
            val i = idx(d)
            val s = rs[i]; val dir = dirAt(i)
            val nx = -dir[1]; val ny = dir[0]
            val stage = stageAt(d)
            val h = hash(i.toLong(), 3L, 5)
            val rot = kotlin.math.atan2(dir[0], -dir[1])
            if (d.toInt() % 150 == 70) {
                // An energy capsule hovering over the track.
                prop("capsule", s[1], s[2], s[3] + 1.4, rot, h % 2)
            }
            if (d.toInt() % 70 == 40 && h % 3 != 0) {
                // A little dome robot on the deck edge.
                val side = if (h % 2 == 0) 1.0 else -1.0
                prop("bot", s[1] + nx * side * (HALF - 0.7), s[2] + ny * side * (HALF - 0.7), s[3], rot + if (side > 0) -1.2 else 1.2, h % 4)
            }
            if (stage == Stage.FACTORY && d.toInt() % 120 == 30) pipeArch(s, dir)
            if (stage != Stage.SKY && d.toInt() % 90 == 60) {
                // A spike strip below the deck edge: don't fall!
                val side = if (h % 2 == 0) 1.0 else -1.0
                val o = side * (HALF + 0.6)
                val ax = s[1] + nx * o - dir[0] * 4; val ay = s[2] + ny * o - dir[1] * 4
                val bx = s[1] + nx * o + dir[0] * 4; val by = s[2] + ny * o + dir[1] * 4
                val gh = min(s[3] - 1.3, groundAt(s[1] + nx * o, s[2] + ny * o))
                quad(ax, ay, gh, bx, by, gh, bx, by, gh + 1.0, ax, ay, gh + 1.0, Tile.SPIKES, 0.0, 0.0, 8.0, 1.0)
            }
            d += 10.0
        }
    }

    private fun gate(d: Double) {
        if (d > route.totalDistance) return
        val i = idx(d)
        val s = rs[i]; val dir = dirAt(i)
        val nx = -dir[1]; val ny = dir[0]
        val h = s[3]
        val w = HALF + 1.3
        for (side in doubleArrayOf(-1.0, 1.0)) {
            box(s[1] + nx * side * w, s[2] + ny * side * w, dir[0], dir[1], 0.9, 0.9, min(h - 3, groundAt(s[1] + nx * side * w, s[2] + ny * side * w)), h + 7.5, Tile.DARK_METAL, Tile.PILLAR)
        }
        // Lintel with a hazard band, and the raised boss shutter under it.
        box(s[1], s[2], dir[0], dir[1], 0.9, w + 0.9, h + 7.5, h + 9.5, Tile.DARK_METAL, Tile.DARK_METAL, bottom = true)
        box(s[1] + dir[0] * 0.95, s[2] + dir[1] * 0.95, dir[0], dir[1], 0.05, w + 0.9, h + 8.0, h + 9.0, Tile.HAZARD, Tile.HAZARD)
        box(s[1], s[2], dir[0], dir[1], 0.4, w - 0.9, h + 5.6, h + 7.5, Tile.SHUTTER, Tile.SHUTTER, bottom = true)
    }

    private fun pipeArch(s: DoubleArray, dir: DoubleArray) {
        val nx = -dir[1]; val ny = dir[0]
        val h = s[3]
        val span = 11.0
        for (side in doubleArrayOf(-1.0, 1.0)) {
            val px = s[1] + nx * side * span; val py = s[2] + ny * side * span
            box(px, py, dir[0], dir[1], 0.6, 0.6, min(h - 2, groundAt(px, py)), h + 8.6, Tile.PIPE, Tile.PIPE)
        }
        // Horizontal run: the pipe tile's bands run along it.
        box(s[1], s[2], nx, ny, span + 0.6, 0.6, h + 7.4, h + 8.6, Tile.PIPE, Tile.PIPE, bottom = true)
    }

    // ---------------------------------------------------------------------------------
    // Kingdom Quest: a 16-bit adventure world.

    private inner class QuestKit : Kit() {
        override val stages = listOf("meadow", "woods", "village", "lake", "mountain", "castle")
        override val half = 2.6

        // Tiles of res://themes/quest_atlas.png.
        val GRASS = 0; val DARK_GRASS = 1; val PATH = 2; val CLIFF = 4; val STONE = 5; val ROOF_RED = 6
        val ROOF_BLUE = 7; val HOUSE = 8; val DOOR = 9; val WINDOW = 10; val WATER = 11; val PLANKS = 12
        val BARK = 14; val FLOWERS = 16; val ROCK = 18; val FENCE = 19; val BANNER = 20
        val CASTLE_WINDOW = 21; val TALL_GRASS = 22; val FLOOR = 23; val SNOW = 24

        fun stage(d: Double) = stages[stageIndex(d)]

        /** Lake stage: open water here (the road crosses on bridges). */
        fun water(x: Double, y: Double, d: Double) = stage(d) == "lake" && vnoise(x * 0.018 + 3.1, y * 0.018) < 0.5

        override fun cellTop(i: Long, j: Long, e: CellInfo): Double? {
            if (e.dist < half + 1.0) return null
            val x = (i + 0.5) * CELL; val y = (j + 0.5) * CELL
            val st = stage(e.d)
            if (water(x, y, e.d)) return floor((e.trackH - 1.6) * 2) / 2
            // Flush with the road nearby; terraced ledges (2 m, 3 m on the mountain) beyond.
            if (e.dist < 11) return e.trackH - 0.05
            val step = if (st == "mountain") 3.0 else 2.0
            var t = floor(dem.height(x, y) / step) * step
            if (e.dist > 18) t += floor(vnoise(i * 0.11, j * 0.11) * 3) * step * min(1.0, (e.dist - 18) / 20)
            if (st == "castle" && e.dist in 13.0..17.5) t = max(t, e.trackH) + 6.0 // the castle walls
            return t
        }

        override fun cellTiles(i: Long, j: Long, e: CellInfo, t: Double): IntArray {
            val x = (i + 0.5) * CELL; val y = (j + 0.5) * CELL
            val st = stage(e.d)
            if (water(x, y, e.d) && e.dist >= half + 1.0) return intArrayOf(WATER, CLIFF)
            val n = vnoise(i * 0.3, j * 0.3)
            val top = when (st) {
                "woods" -> if (n < 0.3) TALL_GRASS else DARK_GRASS
                "mountain" -> if (t > e.trackH + 24) SNOW else ROCK
                "castle" -> if (e.dist < 11 || (e.dist in 13.0..17.5)) FLOOR else GRASS
                else -> when { n > 0.72 -> FLOWERS; n < 0.18 -> TALL_GRASS; else -> GRASS }
            }
            val side = when (st) { "mountain" -> ROCK; "castle" -> STONE; else -> CLIFF }
            return intArrayOf(top, side)
        }

        override fun track(d0: Double, d1: Double) {
            val i0 = idx(d0); val i1 = min(rs.size - 1, idx(d1) + 1)
            for (i in i0 until i1) {
                val a = rs[i]; val b = rs[i + 1]
                val da = dirAt(i); val db = dirAt(i + 1)
                val nax = -da[1]; val nay = da[0]; val nbx = -db[1]; val nby = db[0]
                val ha = a[3] + 0.03; val hb = b[3] + 0.03
                val st = stage(a[0])
                val gMid = groundAt(a[1], a[2])
                val bridge = water(a[1], a[2], a[0]) || a[3] - gMid > 4.5
                val tile = when { bridge -> PLANKS; st == "castle" || st == "village" && i % 40 < 6 -> FLOOR; else -> PATH }
                val w = 2 * half / TILE_M
                quad(a[1] - nax * half, a[2] - nay * half, ha, a[1] + nax * half, a[2] + nay * half, ha,
                    b[1] + nbx * half, b[2] + nby * half, hb, b[1] - nbx * half, b[2] - nby * half, hb, tile, 0.0, b[0] / TILE_M, w, a[0] / TILE_M)
                for (side in doubleArrayOf(-1.0, 1.0)) {
                    val o = side * half
                    val ax = a[1] + nax * o; val ay = a[2] + nay * o; val bx = b[1] + nbx * o; val by = b[2] + nby * o
                    val ga = min(ha - 0.3, groundAt(ax, ay) - 0.3); val gb = min(hb - 0.3, groundAt(bx, by) - 0.3)
                    if (bridge) {
                        // Rails and posts of a wooden bridge.
                        if (i % 2 == 0) box(ax, ay, da[0], da[1], 0.12, 0.12, ha, ha + 1.0, BARK, BARK)
                        if (i % 6 == 0) box(ax, ay, da[0], da[1], 0.25, 0.25, min(ha - 2, ga), ha, BARK, BARK)
                        quad(ax, ay, ha + 0.85, bx, by, hb + 0.85, bx, by, hb + 1.0, ax, ay, ha + 1.0, PLANKS, 0.0, 0.0, 2.0, 0.1)
                        quad(ax, ay, ha - 0.6, bx, by, hb - 0.6, bx, by, hb, ax, ay, ha, PLANKS, 0.0, 0.0, 2.0, 0.25)
                    } else if (ha - ga > 0.5 || hb - gb > 0.5) {
                        // A retaining wall where the road is raised.
                        quad(ax, ay, ga, bx, by, gb, bx, by, hb, ax, ay, ha, if (st == "castle") STONE else CLIFF,
                            a[0] / TILE_M, 0.0, b[0] / TILE_M, (ha - ga) / TILE_M)
                    }
                }
            }
        }

        override fun structures(i: Long, j: Long, e: CellInfo, t: Double) {
            if (e.dist < half + 1.5) return
            val x = (i + 0.5) * CELL; val y = (j + 0.5) * CELL
            if (water(x, y, e.d)) {
                if (hash(i, j, 21) % 1000 < 12) prop("lily", x, y, t + 0.02, 0.0, 0)
                return
            }
            val h = hash(i, j, 13)
            val r = h % 1000
            val rot = (h % 628) / 100.0
            val k = (h / 7) % 8
            // Somewhere in the 5 m cell, not on its edge.
            val px = x + ((h shr 4) % 30 - 15) / 10.0; val py = y + ((h shr 9) % 30 - 15) / 10.0
            when (stage(e.d)) {
                "meadow" -> when {
                    e.dist > 9 && r < 28 -> prop("round_tree", px, py, t, rot, k, 0.9 + (k % 3) * 0.15)
                    r < 70 -> prop("bush", px, py, t, rot, k)
                    r < 82 -> prop("rock", px, py, t, rot, k)
                }
                "woods" -> when {
                    e.dist > 5.5 && r < 210 -> prop(if (k % 4 == 0) "conifer" else "round_tree", px, py, t, rot, k, 1.0 + (k % 3) * 0.2)
                    r < 260 -> prop("bush", px, py, t, rot, k)
                    r < 268 -> prop("mushroom", px, py, t, rot, k)
                }
                "village" -> when {
                    e.dist in 12.0..45.0 && r < 55 -> house(x, y, t, h)
                    e.dist < 12 && r < 40 -> prop("pot", px, py, t, rot, k)
                    e.dist > 9 && r < 75 -> prop("round_tree", px, py, t, rot, k)
                    r < 110 -> prop("bush", px, py, t, rot, k)
                }
                "lake" -> when {
                    e.dist > 8 && r < 40 -> prop("round_tree", px, py, t, rot, k)
                    r < 90 -> prop("bush", px, py, t, rot, k)
                }
                "mountain" -> when {
                    r < 45 -> prop("boulder", px, py, t, rot, k, 0.8 + (k % 4) * 0.3)
                    e.dist > 10 && r < 60 -> prop("conifer", px, py, t, rot, k)
                }
                "castle" -> when {
                    e.dist in 13.0..17.5 -> {
                        // Crenellations on the castle walls.
                        if ((i + j) % 2 == 0L) box(x, y, 1.0, 0.0, 1.0, 1.0, t, t + 1.2, STONE, STONE)
                        if (r < 60) quad(x - 1.0, y, t - 4.5, x + 1.0, y, t - 4.5, x + 1.0, y, t - 0.5, x - 1.0, y, t - 0.5, BANNER, 0.0, 0.0, 1.0, 2.0)
                    }
                    e.dist in 22.0..70.0 && r < 22 -> castleTower(x, y, t, h)
                    r < 40 -> prop("bush", px, py, t, rot, k)
                }
            }
        }

        /** A timber-framed cottage with a red (sometimes blue) gabled roof. */
        fun house(x: Double, y: Double, base: Double, h: Int) {
            val along = (h shr 3) % 2 == 0
            val ux = if (along) 1.0 else 0.0; val uy = if (along) 0.0 else 1.0
            val hw = 3.5; val hd = 2.5; val wall = 3.2
            // Walls: the door on one long side, windows elsewhere.
            box(x, y, ux, uy, hw, hd, base, base + wall, HOUSE, WINDOW)
            val vx = -uy; val vy = ux
            quad(x - ux * 0.6 - vx * (hd + 0.02), y - uy * 0.6 - vy * (hd + 0.02), base,
                x + ux * 0.6 - vx * (hd + 0.02), y + uy * 0.6 - vy * (hd + 0.02), base,
                x + ux * 0.6 - vx * (hd + 0.02), y + uy * 0.6 - vy * (hd + 0.02), base + 2.2,
                x - ux * 0.6 - vx * (hd + 0.02), y - uy * 0.6 - vy * (hd + 0.02), base + 2.2, DOOR, 0.0, 0.0, 1.0, 1.0)
            val roof = if ((h shr 6) % 4 == 0) ROOF_BLUE else ROOF_RED
            val top = base + wall; val ridge = top + 2.4; val eave = 0.5
            fun px(a: Double, b: Double) = x + ux * a + vx * b
            fun py(a: Double, b: Double) = y + uy * a + vy * b
            for (side in doubleArrayOf(-1.0, 1.0)) {
                quad(px(-hw - eave, side * (hd + eave)), py(-hw - eave, side * (hd + eave)), top - 0.3,
                    px(hw + eave, side * (hd + eave)), py(hw + eave, side * (hd + eave)), top - 0.3,
                    px(hw + eave, 0.0), py(hw + eave, 0.0), ridge, px(-hw - eave, 0.0), py(-hw - eave, 0.0), ridge,
                    roof, 0.0, 0.0, (2 * hw + 2 * eave) / TILE_M, (hd + eave) / TILE_M * 1.2)
                // Gable triangle (a degenerate quad).
                val gx = side * hw
                quad(px(gx, -hd), py(gx, -hd), top, px(gx, hd), py(gx, hd), top, px(gx, 0.0), py(gx, 0.0), ridge,
                    px(gx, 0.0), py(gx, 0.0), ridge, HOUSE, 0.0, 0.0, 2 * hd / TILE_M, 1.0)
            }
        }

        /** A round-ish stone tower with a cone roof and a banner. */
        fun castleTower(x: Double, y: Double, base: Double, h: Int) {
            val top = base + 12 + (h shr 4) % 3 * 4.0
            box(x, y, 1.0, 0.0, 2.6, 2.6, base, top, STONE, CASTLE_WINDOW)
            box(x, y, 1.0, 0.0, 3.0, 3.0, top, top + 0.8, STONE, STONE)
            // Pyramid roof.
            val r = 3.2; val apex = top + 0.8 + 5.0
            val c = arrayOf(doubleArrayOf(-r, -r), doubleArrayOf(r, -r), doubleArrayOf(r, r), doubleArrayOf(-r, r))
            for (k in 0 until 4) {
                val a = c[k]; val b = c[(k + 1) % 4]
                quad(x + a[0], y + a[1], top + 0.8, x + b[0], y + b[1], top + 0.8, x, y, apex, x, y, apex, ROOF_BLUE, 0.0, 0.0, 2 * r / TILE_M, 2.0)
            }
            quad(x - 0.7, y - 2.65, top - 6, x + 0.7, y - 2.65, top - 6, x + 0.7, y - 2.65, top - 1, x - 0.7, y - 2.65, top - 1, BANNER, 0.0, 0.0, 1.0, 1.0)
        }

        override fun features(d0: Double, d1: Double) {
            // An archway with banners where each area starts.
            var g = 0.0
            while (g < route.totalDistance) {
                if (g >= d0 && g < d1) arch(g + 10.0)
                g += stageLen()
            }
            var d = (floor(d0 / 10) * 10).coerceAtLeast(20.0)
            while (d < d1) {
                val i = idx(d)
                val s = rs[i]; val dir = dirAt(i)
                val nx = -dir[1]; val ny = dir[0]
                val h = hash(i.toLong(), 9L, 3)
                val rot = kotlin.math.atan2(dir[0], -dir[1])
                val st = stage(d)
                if (d.toInt() % 120 == 50) prop("gem", s[1], s[2], s[3] + 1.3, rot, h % 3)
                if (d.toInt() % 400 == 250) prop("heart", s[1], s[2], s[3] + 1.4, rot, 0)
                if (d.toInt() % 600 == 330) {
                    val side = if (h % 2 == 0) 1.0 else -1.0
                    prop("chest", s[1] + nx * side * (half + 1.4), s[2] + ny * side * (half + 1.4), s[3], rot + if (side > 0) PI_2 else -PI_2, 0)
                }
                if ((st == "meadow" || st == "village") && d.toInt() % 60 == 0 && h % 3 != 0 && !water(s[1], s[2], d)) {
                    // A stretch of picket fence along the road.
                    val side = if (h % 2 == 0) 1.0 else -1.0
                    val o = side * (half + 1.2)
                    val ax = s[1] + nx * o - dir[0] * 6; val ay = s[2] + ny * o - dir[1] * 6
                    val bx = s[1] + nx * o + dir[0] * 6; val by = s[2] + ny * o + dir[1] * 6
                    quad(ax, ay, s[3] - 0.05, bx, by, s[3] - 0.05, bx, by, s[3] + 1.0, ax, ay, s[3] + 1.0, FENCE, 0.0, 0.0, 12.0 / TILE_M * 2, 1.0)
                }
                d += 10.0
            }
        }

        fun arch(d: Double) {
            if (d > route.totalDistance) return
            val i = idx(d)
            val s = rs[i]; val dir = dirAt(i)
            val nx = -dir[1]; val ny = dir[0]
            val h = s[3]
            val w = half + 1.2
            for (side in doubleArrayOf(-1.0, 1.0)) {
                val px = s[1] + nx * side * w; val py = s[2] + ny * side * w
                box(px, py, dir[0], dir[1], 0.8, 0.8, min(h - 1, groundAt(px, py)), h + 5.5, STONE, STONE)
                quad(px - dir[0] * 0.82 - nx * side * 0.5, py - dir[1] * 0.82 - ny * side * 0.5, h + 1.5,
                    px - dir[0] * 0.82 + nx * side * 0.5, py - dir[1] * 0.82 + ny * side * 0.5, h + 1.5,
                    px - dir[0] * 0.82 + nx * side * 0.5, py - dir[1] * 0.82 + ny * side * 0.5, h + 4.8,
                    px - dir[0] * 0.82 - nx * side * 0.5, py - dir[1] * 0.82 - ny * side * 0.5, h + 4.8, BANNER, 0.0, 0.0, 1.0, 1.0)
            }
            box(s[1], s[2], dir[0], dir[1], 0.8, w + 0.8, h + 5.5, h + 6.6, STONE, STONE, bottom = true)
            box(s[1], s[2], dir[0], dir[1], 0.5, w - 0.4, h + 6.6, h + 7.2, ROOF_RED, STONE)
            prop("sign", s[1] + nx * (half + 2.2) - dir[0] * 3, s[2] + ny * (half + 2.2) - dir[1] * 3, groundAt(s[1] + nx * (half + 2.2), s[2] + ny * (half + 2.2)).coerceAtLeast(h - 0.05), kotlin.math.atan2(dir[0], -dir[1]), 0)
        }
    }

    private val PI_2 = Math.PI / 2
}
