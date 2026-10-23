package com.bike.trainer.ui.ride

import android.content.Context
import com.bike.trainer.route.LocalProjection
import com.bike.trainer.route.Route
import com.bike.trainer.route.TerrainTiles
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ground height (metres) at a local position (x = east, y = north, metres from the
 * route's first point). GPX routes sample the real DEM tiles; generated routes —
 * and any DEM gap — use procedural hills that follow the route's own profile.
 * Only ever used from the single world-builder thread.
 */
class HeightField(
    private val context: Context,
    private val route: Route,
    private val allowNetwork: Boolean,
) {
    private val useDem = route.id != null && route.points.isNotEmpty()
    private val proj = LocalProjection(route)

    private val tiles = HashMap<Long, FloatArray?>()

    // Route points bucketed for nearest-point lookups (procedural fallback).
    private val buckets = HashMap<Long, MutableList<Int>>()
    private val minElevation = route.minElevation

    init {
        val step = (25.0 / route.stepMeters.coerceAtLeast(1.0)).toInt().coerceAtLeast(1)
        var i = 0
        while (i < route.points.size) {
            val p = route.points[i]
            buckets.getOrPut(key(bucket(p.x), bucket(p.y))) { ArrayList() }.add(i)
            i += step
        }
    }

    fun height(x: Double, y: Double): Double {
        if (useDem) {
            dem(x, y)?.let { return it }
        }
        return procedural(x, y)
    }

    private fun dem(x: Double, y: Double): Double? {
        val lat = proj.lat(y)
        val lon = proj.lon(x)
        val gx = TerrainTiles.tileX(lon) * 256.0 - 0.5
        val gy = TerrainTiles.tileY(lat) * 256.0 - 0.5
        val ix = floor(gx).toInt()
        val iy = floor(gy).toInt()
        val fx = gx - ix
        val fy = gy - iy
        val h00 = pixel(ix, iy) ?: return null
        val h10 = pixel(ix + 1, iy) ?: return null
        val h01 = pixel(ix, iy + 1) ?: return null
        val h11 = pixel(ix + 1, iy + 1) ?: return null
        val top = h00 + (h10 - h00) * fx
        val bot = h01 + (h11 - h01) * fx
        return top + (bot - top) * fy
    }

    private fun pixel(gx: Int, gy: Int): Double? {
        val tx = gx shr 8
        val ty = gy shr 8
        val k = key(tx, ty)
        if (!tiles.containsKey(k)) {
            if (tiles.size > 32) tiles.clear()
            var d = TerrainTiles.decode(context, tx, ty)
            if (d == null && allowNetwork && TerrainTiles.fetch(context, tx, ty)) {
                d = TerrainTiles.decode(context, tx, ty)
            }
            tiles[k] = d
        }
        val data = tiles[k] ?: return null
        return data[(gy and 255) * 256 + (gx and 255)].toDouble()
    }

    /** Hills that rise away from the road, anchored to the route's own elevation. */
    private fun procedural(x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        var base = minElevation
        val bx = bucket(x)
        val by = bucket(y)
        for (r in intArrayOf(2, 6)) {
            for (i in bx - r..bx + r) for (j in by - r..by + r) {
                buckets[key(i, j)]?.forEach { idx ->
                    val p = route.points[idx]
                    val d = hypot(p.x - x, p.y - y)
                    if (d < best) { best = d; base = p.elevation }
                }
            }
            if (best < Double.MAX_VALUE) break
        }
        val d = if (best == Double.MAX_VALUE) 2000.0 else best
        val near = min(1.0, d / 120.0)
        return base +
            (d / 1000.0).pow(1.3) * 140.0 +
            near * (valueNoise(x / 650.0, y / 650.0) * 90.0 + valueNoise(x / 160.0, y / 160.0) * 14.0)
    }

    private fun bucket(v: Double) = floor(v / BUCKET).toInt()

    companion object {
        private const val BUCKET = 200.0
        fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
    }
}

/** Deterministic 0..1 hash of an integer pair (+ salt). */
internal fun hash01(a: Int, b: Int, salt: Int = 0): Double {
    var h = a * 374761393 + b * 668265263 + salt * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7FFFFFFF) / 2147483647.0
}

/** Smooth value noise in -1..1. */
internal fun valueNoise(x: Double, y: Double): Double {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - ix
    val fy = y - iy
    val sx = fx * fx * (3 - 2 * fx)
    val sy = fy * fy * (3 - 2 * fy)
    val a = hash01(ix, iy)
    val b = hash01(ix + 1, iy)
    val c = hash01(ix, iy + 1)
    val d = hash01(ix + 1, iy + 1)
    val top = a + (b - a) * sx
    val bot = c + (d - c) * sx
    return (top + (bot - top) * sy) * 2.0 - 1.0
}

/**
 * One window of low-poly world around the rider, as interleaved
 * [x, y, z, r, g, b, shade] float triangles in GL space (X = east, Y = up,
 * Z = -north). Terrain also gets [ground]: a texture of the real land use / water /
 * streets, covering [originX]..+[size] east and [originY]..+[size] north. Keeps its
 * height grid so the camera can sit exactly on the rendered surface.
 */
class WorldMesh(
    val terrain: FloatArray,
    val trees: FloatArray,
    val road: FloatArray,
    val markings: FloatArray,
    val buildings: FloatArray,
    val ground: android.graphics.Bitmap?,
    val originX: Double,
    val originY: Double,
    private val n: Int,
    private val heights: DoubleArray,
) {
    val size: Double get() = n * SPACING

    /** Height of the rendered terrain surface (same triangulation), or null outside. */
    fun heightAt(x: Double, y: Double): Double? {
        val fx = (x - originX) / SPACING
        val fy = (y - originY) / SPACING
        val i = floor(fx).toInt()
        val j = floor(fy).toInt()
        if (i < 0 || j < 0 || i >= n || j >= n) return null
        val u = fx - i
        val v = fy - j
        val h00 = heights[j * (n + 1) + i]
        val h10 = heights[j * (n + 1) + i + 1]
        val h01 = heights[(j + 1) * (n + 1) + i]
        val h11 = heights[(j + 1) * (n + 1) + i + 1]
        return if (u >= v) h00 + u * (h10 - h00) + v * (h11 - h10)
        else h00 + v * (h01 - h00) + u * (h11 - h01)
    }

    companion object {
        const val SPACING = 40.0
        const val CELLS = 150
    }
}

/**
 * Builds [WorldMesh] windows: terrain painted with the real OpenStreetMap land
 * use, the route as a road, extruded buildings and trees where the map has them.
 */
object WorldBuilder {
    private const val ROAD_HALF_WIDTH = 3.5
    private const val ROAD_LIFT = 0.3
    private const val ROAD_STEP = 4.0
    private const val TREE_BAND = 450.0
    private const val BUILDING_BAND = 500.0
    private const val MAX_BUILDINGS = 12_000
    private const val TREE_CLEARANCE = 9.0
    private const val TREELINE = 2050.0

    // Sun direction (normalised), from the south-west and fairly high.
    private val light = floatArrayOf(-0.35f, 0.85f, 0.40f).also { l ->
        val m = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])
        for (i in 0..2) l[i] /= m
    }

    fun build(field: HeightField, osm: OsmSource, route: Route, centerDist: Double, riderDist: Double): WorldMesh {
        val s = WorldMesh.SPACING
        val n = WorldMesh.CELLS
        val c = route.pointAt(centerDist)
        // Snap the origin to the grid so overlapping windows produce identical terrain.
        val ox = floor((c.x - n * s / 2) / s) * s
        val oy = floor((c.y - n * s / 2) / s) * s
        val heights = DoubleArray((n + 1) * (n + 1))
        for (j in 0..n) for (i in 0..n) {
            heights[j * (n + 1) + i] = field.height(ox + i * s, oy + j * s)
        }
        val shell = WorldMesh(
            FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), null, ox, oy, n, heights,
        )

        // ---- Real map data for this window ----
        val win = osm.window(ox, oy, ox + n * s, oy + n * s)
        val painter = if (win.loadedAreas.isNotEmpty()) GroundPainter(ox, oy, n * s).also { it.paint(win) } else null

        // ---- Road samples inside the window ----
        // Snapped so the dashed centre line doesn't shift between windows.
        val from = (floor((riderDist - 1500.0) / 12.0) * 12.0).coerceAtLeast(0.0)
        val to = (riderDist + 4500.0).coerceAtMost(route.totalDistance)
        val rx = ArrayList<Double>()
        val ry = ArrayList<Double>()
        var d = from
        while (d <= to) {
            val p = route.pointAt(d)
            rx.add(p.x); ry.add(p.y)
            d += ROAD_STEP
        }
        val roadBuckets = HashMap<Long, MutableList<Int>>()
        for (k in rx.indices) {
            roadBuckets.getOrPut(HeightField.key(floor(rx[k] / 20.0).toInt(), floor(ry[k] / 20.0).toInt())) { ArrayList() }.add(k)
        }

        val terrain = FloatList(n * n * 42)
        val cellRgb = FloatArray(3)
        for (j in 0 until n) for (i in 0 until n) {
            val x0 = ox + i * s
            val y0 = oy + j * s
            val h00 = heights[j * (n + 1) + i]
            val h10 = heights[j * (n + 1) + i + 1]
            val h01 = heights[(j + 1) * (n + 1) + i]
            val h11 = heights[(j + 1) * (n + 1) + i + 1]
            val wi = floor(ox / s).toInt() + i
            val wj = floor(oy / s).toInt() + j
            terrainTri(terrain, x0, y0, h00, x0 + s, y0, h10, x0 + s, y0 + s, h11, wi, wj, 0, cellRgb)
            terrainTri(terrain, x0, y0, h00, x0 + s, y0 + s, h11, x0, y0 + s, h01, wi, wj, 1, cellRgb)
        }

        // ---- Road ribbon + markings (heights from the rendered surface) ----
        val road = FloatList(rx.size * 42)
        val marks = FloatList(rx.size * 84)
        for (k in 0 until rx.size - 1) {
            val ax = rx[k]; val ay = ry[k]; val bx = rx[k + 1]; val by = ry[k + 1]
            val dx = bx - ax; val dy = by - ay
            val len = hypot(dx, dy)
            if (len < 0.01) continue
            // Right-hand vector of the travel direction.
            val px = dy / len; val py = -dx / len
            strip(road, shell, ax, ay, bx, by, px, py, -ROAD_HALF_WIDTH, ROAD_HALF_WIDTH, ROAD_LIFT, ROAD_RGB)
            strip(marks, shell, ax, ay, bx, by, px, py, -3.2, -2.95, ROAD_LIFT + 0.04, LINE_RGB)
            strip(marks, shell, ax, ay, bx, by, px, py, 2.95, 3.2, ROAD_LIFT + 0.04, LINE_RGB)
            // Dashed centre line: 4 of every 12 metres.
            if ((from + k * ROAD_STEP) % 12.0 < ROAD_STEP) {
                strip(marks, shell, ax, ay, bx, by, px, py, -0.08, 0.08, ROAD_LIFT + 0.04, LINE_RGB)
            }
        }

        // Cells near the road, for buildings and trees.
        fun nearMask(radius: Double): BooleanArray {
            val near = BooleanArray(n * n)
            val reach = (radius / s).toInt()
            var k = 0
            while (k < rx.size) {
                val ci = floor((rx[k] - ox) / s).toInt()
                val cj = floor((ry[k] - oy) / s).toInt()
                for (j in cj - reach..cj + reach) for (i in ci - reach..ci + reach) {
                    if (i in 0 until n && j in 0 until n) near[j * n + i] = true
                }
                k += 10
            }
            return near
        }
        fun cellOf(mask: BooleanArray, x: Double, y: Double): Boolean {
            val i = floor((x - ox) / s).toInt()
            val j = floor((y - oy) / s).toInt()
            return i in 0 until n && j in 0 until n && mask[j * n + i]
        }

        // ---- Buildings: real OSM footprints and heights, near the road ----
        val buildings = FloatList(200_000)
        val nearBuildings = nearMask(BUILDING_BAND)
        var placed = 0
        for (f in win.features) {
            if (placed >= MAX_BUILDINGS) break
            if (f.layer != "building") continue
            val cx = (f.minX + f.maxX) / 2; val cy = (f.minY + f.maxY) / 2
            if (!cellOf(nearBuildings, cx, cy)) continue
            // Never let the road run through a building (GPS tracks can drift).
            if (routeInside(f, rx, ry, roadBuckets)) continue
            val seed = (floor(cx).toInt() * 73856093) xor (floor(cy).toInt() * 19349663)
            if (BuildingMesher.add(buildings, f, { x, y -> shell.heightAt(x, y) }, light, seed)) placed++
        }

        // ---- Trees: where the map has woods / parks (or roadside forest without data) ----
        val nearTrees = nearMask(TREE_BAND)
        val trees = FloatList(120_000)
        for (j in 0 until n) for (i in 0 until n) {
            if (!nearTrees[j * n + i]) continue
            val wi = floor(ox / s).toInt() + i
            val wj = floor(oy / s).toInt() + j
            for (t in 0..3) {
                val tx = ox + (i + hash01(wi, wj, 11 + t)) * s
                val ty = oy + (j + hash01(wi, wj, 23 + t)) * s
                val h = shell.heightAt(tx, ty) ?: continue
                if (h > TREELINE) continue
                val hx = shell.heightAt(tx + 4, ty) ?: h
                val hy = shell.heightAt(tx, ty + 4) ?: h
                val slope = hypot(hx - h, hy - h) / 4.0
                if (slope > 0.8) continue
                val landClass = painter?.classAt(tx, ty) ?: LandClass.NONE
                val density = when (landClass) {
                    LandClass.FOREST -> 0.85
                    LandClass.GRASS -> if (t < 2) 0.30 else 0.0
                    LandClass.URBAN -> if (t < 2) 0.10 else 0.0
                    LandClass.LAND -> if (t < 2) 0.15 else 0.0
                    LandClass.WETLAND -> if (t < 1) 0.15 else 0.0
                    LandClass.FARM -> if (t < 1) 0.02 else 0.0
                    // No map data here: the old roadside-forest guess.
                    LandClass.NONE -> if (t < 2) 0.55 * (1.0 - slope) * (if (h > TREELINE - 250) 0.5 else 1.0) else 0.0
                    else -> 0.0 // water, roads, buildings, rock / sand / ice
                }
                if (hash01(wi, wj, 37 + t) > density) continue
                if (nearRoad(tx, ty, rx, ry, roadBuckets)) continue
                val tall = 7.0 + hash01(wi, wj, 53 + t) * 8.0
                val broadleaf = h < 1000 && landClass != LandClass.NONE && hash01(wi, wj, 89 + t) > 0.3
                if (broadleaf) blob(trees, tx, ty, h - 0.5, tall * 0.9, hash01(wi, wj, 71 + t) > 0.5)
                else cone(trees, tx, ty, h - 0.5, tall, tall * 0.3, hash01(wi, wj, 71 + t) > 0.5)
            }
        }
        painter?.recycleClassRaster()

        return WorldMesh(
            terrain.toArray(), trees.toArray(), road.toArray(), marks.toArray(), buildings.toArray(),
            painter?.texture, ox, oy, n, heights,
        )
    }

    private fun routeInside(
        f: OsmFeature, rx: List<Double>, ry: List<Double>, b: HashMap<Long, MutableList<Int>>,
    ): Boolean {
        val pad = ROAD_HALF_WIDTH
        for (i in floor((f.minX - pad) / 20.0).toInt()..floor((f.maxX + pad) / 20.0).toInt()) {
            for (j in floor((f.minY - pad) / 20.0).toInt()..floor((f.maxY + pad) / 20.0).toInt()) {
                b[HeightField.key(i, j)]?.forEach { k ->
                    val x = rx[k]; val y = ry[k]
                    if (x < f.minX - pad || x > f.maxX + pad || y < f.minY - pad || y > f.maxY + pad) return@forEach
                    for (ring in f.parts) {
                        val cnt = ring.size / 2
                        if (cnt >= 3 && BuildingMesher.contains(ring, cnt, x, y)) return true
                    }
                }
            }
        }
        return false
    }

    private fun nearRoad(
        x: Double, y: Double, rx: List<Double>, ry: List<Double>, b: HashMap<Long, MutableList<Int>>,
    ): Boolean {
        val bx = floor(x / 20.0).toInt()
        val by = floor(y / 20.0).toInt()
        for (i in bx - 1..bx + 1) for (j in by - 1..by + 1) {
            b[HeightField.key(i, j)]?.forEach { k ->
                if (hypot(rx[k] - x, ry[k] - y) < TREE_CLEARANCE) return true
            }
        }
        return false
    }

    private fun terrainTri(
        out: FloatList,
        ax: Double, ay: Double, ah: Double,
        bx: Double, by: Double, bh: Double,
        cx: Double, cy: Double, ch: Double,
        wi: Int, wj: Int, salt: Int, rgb: FloatArray,
    ) {
        // GL space: X = east, Y = up, Z = -north.
        val ux = bx - ax; val uy = bh - ah; val uz = -(by - ay)
        val vx = cx - ax; val vy = ch - ah; val vz = -(cy - ay)
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val m = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9)
        nx /= m; ny /= m; nz /= m
        if (ny < 0) { nx = -nx; ny = -ny; nz = -nz }
        val h = (ah + bh + ch) / 3.0
        val slope = 1.0 - ny
        val jitter = (hash01(wi, wj, salt) - 0.5) * 0.08
        val meadow = valueNoise(ax / 300.0, ay / 300.0)
        when {
            h > 2700 && slope < 0.45 -> set(rgb, 0.93, 0.95, 0.97)
            slope > 0.38 || h > 2350 -> set(rgb, 0.58, 0.56, 0.53)
            h > 1900 -> set(rgb, 0.56, 0.68, 0.36)
            meadow > 0.2 -> set(rgb, 0.40, 0.60, 0.26)
            else -> set(rgb, 0.45, 0.66, 0.29)
        }
        val shade = (0.55 + 0.5 * max(0.0, nx * light[0] + ny * light[1] + nz * light[2]) + jitter).toFloat()
        val r = rgb[0] * shade; val g = rgb[1] * shade; val bl = rgb[2] * shade
        out.vertex(ax, ah, -ay, r, g, bl, shade)
        out.vertex(bx, bh, -by, r, g, bl, shade)
        out.vertex(cx, ch, -cy, r, g, bl, shade)
    }

    /** A flat strip between lateral offsets [l0]..[l1] along one road segment. */
    private fun strip(
        out: FloatList, mesh: WorldMesh,
        ax: Double, ay: Double, bx: Double, by: Double,
        px: Double, py: Double, l0: Double, l1: Double, lift: Double, rgb: FloatArray,
    ) {
        val p = arrayOf(
            doubleArrayOf(ax + px * l0, ay + py * l0),
            doubleArrayOf(ax + px * l1, ay + py * l1),
            doubleArrayOf(bx + px * l1, by + py * l1),
            doubleArrayOf(bx + px * l0, by + py * l0),
        )
        val h = DoubleArray(4)
        for (i in 0..3) h[i] = (mesh.heightAt(p[i][0], p[i][1]) ?: return) + lift
        for (i in intArrayOf(0, 1, 2, 0, 2, 3)) {
            out.vertex(p[i][0], h[i], -p[i][1], rgb[0], rgb[1], rgb[2])
        }
    }

    /** A five-sided low-poly conifer. */
    private fun cone(out: FloatList, x: Double, y: Double, base: Double, tall: Double, radius: Double, dark: Boolean) {
        val sides = 5
        val col = if (dark) floatArrayOf(0.17f, 0.40f, 0.21f) else floatArrayOf(0.23f, 0.49f, 0.23f)
        val ah = base + tall
        val baseH = base + 1.2
        for (s in 0 until sides) {
            val a0 = 2 * PI * s / sides
            val a1 = 2 * PI * (s + 1) / sides
            val x0 = x + cos(a0) * radius; val y0 = y + sin(a0) * radius
            val x1 = x + cos(a1) * radius; val y1 = y + sin(a1) * radius
            // Face normal from its outward middle direction (cheap, good enough).
            val am = (a0 + a1) / 2
            val nx = cos(am) * 0.8; val nz = -sin(am) * 0.8; val ny = 0.45
            val shade = (0.55 + 0.55 * max(0.0, nx * light[0] + ny * light[1] + nz * light[2])).toFloat()
            out.vertex(x, ah, -y, col[0] * shade, col[1] * shade, col[2] * shade)
            out.vertex(x0, baseH, -y0, col[0] * shade, col[1] * shade, col[2] * shade)
            out.vertex(x1, baseH, -y1, col[0] * shade, col[1] * shade, col[2] * shade)
        }
        trunk(out, x, y, base, baseH, radius * 0.12)
    }

    /** A low-poly broadleaf tree: a faceted crown (octahedron) on a trunk. */
    private fun blob(out: FloatList, x: Double, y: Double, base: Double, tall: Double, dark: Boolean) {
        val col = if (dark) floatArrayOf(0.27f, 0.50f, 0.20f) else floatArrayOf(0.36f, 0.58f, 0.24f)
        val r = tall * 0.33
        val trunkTop = base + tall - 2 * r
        val cy = trunkTop + r
        trunk(out, x, y, base, trunkTop + r * 0.3, r * 0.12)
        for (q in 0 until 4) {
            val a0 = PI / 2 * q + PI / 4
            val a1 = a0 + PI / 2
            val x0 = x + cos(a0) * r; val y0 = y + sin(a0) * r
            val x1 = x + cos(a1) * r; val y1 = y + sin(a1) * r
            val am = (a0 + a1) / 2
            for (up in intArrayOf(1, -1)) {
                val nx = cos(am) * 0.7; val nz = -sin(am) * 0.7; val ny = 0.7 * up
                val shade = (0.55 + 0.55 * max(0.0, nx * light[0] + ny * light[1] + nz * light[2])).toFloat()
                out.vertex(x, cy + up * r * 1.1, -y, col[0] * shade, col[1] * shade, col[2] * shade)
                out.vertex(x0, cy, -y0, col[0] * shade, col[1] * shade, col[2] * shade)
                out.vertex(x1, cy, -y1, col[0] * shade, col[1] * shade, col[2] * shade)
            }
        }
    }

    private fun trunk(out: FloatList, x: Double, y: Double, base: Double, top: Double, tr: Double) {
        val c = floatArrayOf(0.36f, 0.26f, 0.18f)
        for (s in 0 until 4) {
            val a0 = PI / 2 * s; val a1 = PI / 2 * (s + 1)
            val x0 = x + cos(a0) * tr; val y0 = y + sin(a0) * tr
            val x1 = x + cos(a1) * tr; val y1 = y + sin(a1) * tr
            out.vertex(x0, base, -y0, c[0], c[1], c[2])
            out.vertex(x1, base, -y1, c[0], c[1], c[2])
            out.vertex(x1, top, -y1, c[0], c[1], c[2])
            out.vertex(x0, base, -y0, c[0], c[1], c[2])
            out.vertex(x1, top, -y1, c[0], c[1], c[2])
            out.vertex(x0, top, -y0, c[0], c[1], c[2])
        }
    }

    private fun set(rgb: FloatArray, r: Double, g: Double, b: Double) {
        rgb[0] = r.toFloat(); rgb[1] = g.toFloat(); rgb[2] = b.toFloat()
    }

    private val ROAD_RGB = floatArrayOf(0.30f, 0.31f, 0.33f)
    private val LINE_RGB = floatArrayOf(0.92f, 0.92f, 0.90f)
}

/** Growable float buffer of [x, y, z, r, g, b, shade] vertices (no boxing). */
class FloatList(initial: Int) {
    private var data = FloatArray(initial.coerceAtLeast(16))
    var size = 0
        private set

    fun vertex(x: Double, y: Double, z: Double, r: Float, g: Float, b: Float, shade: Float = 1f) {
        if (size + STRIDE > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x.toFloat(); data[size++] = y.toFloat(); data[size++] = z.toFloat()
        data[size++] = r; data[size++] = g; data[size++] = b; data[size++] = shade
    }

    fun toArray(): FloatArray = data.copyOf(size)

    fun clear() { size = 0 }

    companion object {
        const val STRIDE = 7
    }
}
