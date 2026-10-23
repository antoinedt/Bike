package com.bike.trainer.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * One segmented Street View frame: a [W] x [H] grid of cells (row 0 at the top),
 * each with its class (see [Seg]) and the photo's mean colour there.
 */
class SegFrame(
    /** Camera position, local metres (x east, y north). */
    val x: Double, val y: Double,
    /** Compass heading of the view (radians, 0 = north, clockwise). */
    val heading: Double,
    val labels: ByteArray,
    val colours: IntArray,
) {
    companion object {
        const val W = 65
        const val H = 49
    }
}

/** Cityscapes classes as output by the on-device segmentation model. */
object Seg {
    const val ROAD = 0; const val SIDEWALK = 1; const val BUILDING = 2; const val WALL = 3; const val FENCE = 4
    const val POLE = 5; const val VEGETATION = 8; const val TERRAIN = 9; const val SKY = 10
    const val CAR = 13
}

/**
 * Reads segmented Street View frames against the map: which colour / material each
 * building's façade has, and where the street trees stand. The frames were taken
 * along the route looking ahead (85° wide, 4:3, level), from the panorama spot.
 */
class PhotoAnalysis(private val buildings: List<OsmArea>) {

    class Result(
        val facts: Map<Long, BuildingFacts>,
        val trees: List<DoubleArray>,
        val frames: Int,
    )

    private class Footprint(val area: OsmArea, val ring: DoubleArray, val n: Int, val height: Double) {
        val samples = ArrayList<Int>()
    }

    private val prints = ArrayList<Footprint>()
    private val grid = HashMap<Long, MutableList<Footprint>>()

    init {
        for (b in buildings) {
            val r = b.outers.maxByOrNull { abs(Geometry.area(it)) } ?: continue
            val n = Geometry.count(r)
            if (n < 3) continue
            val t = b.tags
            val h = parseMeters(t["height"]) ?: t["building:levels"]?.toDoubleOrNull()?.let { it * 3.2 } ?: 12.0
            val fp = Footprint(b, r, n, h)
            prints.add(fp)
            val bb = Geometry.bbox(listOf(r))
            for (i in floor(bb[0] / G).toLong()..floor(bb[2] / G).toLong()) for (j in floor(bb[1] / G).toLong()..floor(bb[3] / G).toLong()) {
                grid.getOrPut(key(i, j)) { ArrayList() }.add(fp)
            }
        }
    }

    fun analyse(frames: Sequence<SegFrame>): Result {
        val treeVotes = HashMap<Long, DoubleArray>() // 4 m cells: sum x, sum y, count
        var count = 0
        val tanH = tan(HFOV / 2)
        val tanV = tanH * 3.0 / 4.0 // 640 x 480 frames
        for (f in frames) {
            count++
            for (c in 0 until SegFrame.W) {
                val az = f.heading + atan(((c + 0.5) / SegFrame.W * 2 - 1) * tanH)
                val dx = sin(az); val dy = cos(az)
                val hit = cast(f.x, f.y, dx, dy)
                // Façade colours: building cells within the hit building's height span.
                if (hit != null) {
                    val (fp, dist) = hit
                    val lo = atan(-CAM_H / dist); val hi = atan((fp.height - CAM_H) / dist)
                    for (r in 0 until SegFrame.H) {
                        val k = r * SegFrame.W + c
                        val cls = f.labels[k].toInt()
                        if (cls != Seg.BUILDING && cls != Seg.WALL) continue
                        val el = atan((1 - (r + 0.5) / SegFrame.H * 2) * tanV)
                        if (el < lo - 0.03 || el > hi + 0.03) continue
                        fp.samples.add(f.colours[k])
                    }
                }
                // Trees: the lowest vegetation cell of this column, if it meets the ground
                // below the horizon (trunk / hedge base), gives the distance.
                var veg = 0
                var lowest = -1
                for (r in 0 until SegFrame.H) if (f.labels[r * SegFrame.W + c].toInt() == Seg.VEGETATION) { veg++; lowest = r }
                if (veg < 4 || lowest < 0) continue
                val below = if (lowest + 1 < SegFrame.H) f.labels[(lowest + 1) * SegFrame.W + c].toInt() else -1
                val el = atan((1 - (lowest + 1.0) / SegFrame.H * 2) * tanV)
                val d = if (el < -0.02 && below in GROUND) CAM_H / tan(-el) else continue
                if (d < 3.0 || d > 45.0) continue
                if (hit != null && d > hit.second + 1.0) continue
                val tx = f.x + dx * d; val ty = f.y + dy * d
                val kk = key(floor(tx / 4).toLong(), floor(ty / 4).toLong())
                val v = treeVotes.getOrPut(kk) { DoubleArray(3) }
                v[0] += tx; v[1] += ty; v[2] += 1.0
            }
        }
        // Trees: cells seen from at least two columns, merged to ~7 m spacing.
        val trees = ArrayList<DoubleArray>()
        val taken = HashSet<Long>()
        for (v in treeVotes.values.sortedByDescending { it[2] }) {
            if (v[2] < 2) continue
            val x = v[0] / v[2]; val y = v[1] / v[2]
            val i = floor(x / 7).toLong(); val j = floor(y / 7).toLong()
            var near = false
            for (a in -1..1) for (b in -1..1) if (key(i + a, j + b) in taken) near = true
            if (near) continue
            taken.add(key(i, j))
            if (inside(x, y)) continue
            trees.add(doubleArrayOf(x, y))
        }
        val facts = HashMap<Long, BuildingFacts>()
        for (fp in prints) {
            if (fp.samples.size < MIN_SAMPLES) continue
            facts[fp.area.id] = describe(fp.samples)
        }
        return Result(facts, trees, count)
    }

    private fun inside(x: Double, y: Double): Boolean =
        grid[key(floor(x / G).toLong(), floor(y / G).toLong())]?.any { Geometry.contains(it.ring, x, y, it.n) } ?: false

    /** First footprint edge hit by the ray, within [MAX_DIST]: (footprint, distance). */
    private fun cast(x: Double, y: Double, dx: Double, dy: Double): Pair<Footprint, Double>? {
        var best: Footprint? = null
        var bestT = MAX_DIST
        val seen = HashSet<Footprint>()
        var t = 0.0
        while (t < bestT) {
            val px = x + dx * t; val py = y + dy * t
            val list = grid[key(floor(px / G).toLong(), floor(py / G).toLong())]
            if (list != null) for (fp in list) {
                if (!seen.add(fp)) continue
                val r = fp.ring
                for (i in 0 until fp.n) {
                    val j = (i + 1) % fp.n
                    val s = segment(x, y, dx, dy, r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1])
                    if (s in 0.5..bestT) { bestT = s; best = fp }
                }
            }
            t += G / 2
        }
        return best?.let { it to bestT }
    }

    companion object {
        private const val G = 20.0
        private const val HFOV = 85.0 * PI / 180
        private const val CAM_H = 2.5
        private const val MAX_DIST = 70.0
        private const val MIN_SAMPLES = 8
        private val GROUND = setOf(Seg.ROAD, Seg.SIDEWALK, Seg.TERRAIN, Seg.CAR, Seg.FENCE, Seg.POLE)

        private fun key(i: Long, j: Long) = (i shl 32) or (j and 0xFFFFFFFFL)

        /** Ray (x,y)+t(dx,dy) against segment a-b: t, or -1. */
        private fun segment(x: Double, y: Double, dx: Double, dy: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val ex = bx - ax; val ey = by - ay
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-9) return -1.0
            val wx = ax - x; val wy = ay - y
            val t = (wx * ey - wy * ex) / den
            val u = (wx * dy - wy * dx) / den
            return if (u in 0.0..1.0) t else -1.0
        }

        /**
         * A building's colour and material from its photo samples: the median colour
         * (robust to windows, shade and passing cars), lifted a little to read as a
         * painted façade in the cel-shaded world.
         */
        fun describe(samples: List<Int>): BuildingFacts {
            val hsv = samples.map { hsv(it) }
            // Median by brightness, then average the middle half for hue / saturation.
            val sorted = hsv.sortedBy { it[2] }
            val mid = sorted.subList(sorted.size / 4, max(sorted.size / 4 + 1, sorted.size * 3 / 4))
            var sx = 0.0; var sy = 0.0; var s = 0.0; var v = 0.0
            for (p in mid) { sx += cos(p[0]) * p[1]; sy += sin(p[0]) * p[1]; s += p[1]; v += p[2] }
            s /= mid.size; v /= mid.size
            var h = kotlin.math.atan2(sy, sx); if (h < 0) h += 2 * PI
            // Spread of brightness: curtain walls reflect sky and interiors.
            val spread = sorted[sorted.size * 9 / 10][2] - sorted[sorted.size / 10][2]
            val hueDeg = h * 180 / PI
            val material = when {
                hueDeg in 180.0..235.0 && s > 0.10 && spread > 0.35 -> Material.GLASS
                (hueDeg < 28 || hueDeg > 340) && s > 0.30 && v in 0.15..0.70 -> Material.BRICK
                s < 0.09 && v in 0.30..0.72 -> Material.CONCRETE
                hueDeg in 25.0..55.0 && s in 0.10..0.32 && v > 0.50 -> Material.STONE
                else -> Material.PLASTER
            }
            val colour = rgb(h, min(0.85, s * 1.05), 0.25 + 0.75 * min(1.0, v * 1.05))
            return BuildingFacts(colour, material)
        }

        private fun hsv(c: Int): DoubleArray {
            val r = ((c shr 16) and 0xFF) / 255.0; val g = ((c shr 8) and 0xFF) / 255.0; val b = (c and 0xFF) / 255.0
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
            val h = when {
                d < 1e-6 -> 0.0
                mx == r -> ((g - b) / d).mod(6.0)
                mx == g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } * PI / 3
            return doubleArrayOf(h, if (mx <= 0) 0.0 else d / mx, mx)
        }

        private fun rgb(h: Double, s: Double, v: Double): Int {
            val hh = (h * 3 / PI).mod(6.0)
            val c = v * s; val x = c * (1 - abs(hh.mod(2.0) - 1)); val m = v - c
            val (r, g, b) = when (hh.toInt()) {
                0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
            }
            fun ch(u: Double) = ((u + m) * 255).toInt().coerceIn(0, 255)
            return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}
