package com.bike.trainer.world

import kotlin.math.abs
import kotlin.math.hypot

/** Small 2D geometry helpers on interleaved x,y rings (local metres). */
object Geometry {

    /** Number of points, dropping a repeated closing point. */
    fun count(r: DoubleArray): Int {
        var n = r.size / 2
        if (n >= 2 && r[0] == r[2 * n - 2] && r[1] == r[2 * n - 1]) n--
        return n
    }

    /** Signed area (counter-clockwise positive, y north). */
    fun area(r: DoubleArray, n: Int = count(r)): Double {
        var s = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += r[2 * i] * r[2 * j + 1] - r[2 * j] * r[2 * i + 1]
        }
        return s / 2
    }

    /** Ring as counter-clockwise points without the closing duplicate. */
    fun ccw(r: DoubleArray): DoubleArray {
        val n = count(r)
        val out = r.copyOf(2 * n)
        if (area(out, n) >= 0) return out
        return DoubleArray(2 * n) { i -> val k = n - 1 - i / 2; out[2 * k + (i and 1)] }
    }

    /** Drop nearly collinear / duplicate points (keeps footprints clean). */
    fun simplify(r: DoubleArray, angleTol: Double = 0.06): DoubleArray {
        var pts = r
        var changed = true
        while (changed && pts.size / 2 > 3) {
            changed = false
            val n = pts.size / 2
            for (i in 0 until n) {
                val a = (i + n - 1) % n; val c = (i + 1) % n
                val ax = pts[2 * a]; val ay = pts[2 * a + 1]
                val bx = pts[2 * i]; val by = pts[2 * i + 1]
                val cx = pts[2 * c]; val cy = pts[2 * c + 1]
                val d1 = hypot(bx - ax, by - ay); val d2 = hypot(cx - bx, cy - by)
                val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
                if (d1 < 0.3 || d2 < 0.3 || abs(cross) / (d1 * d2 + 1e-9) < angleTol) {
                    pts = pts.copyOfRange(0, 2 * i) + pts.copyOfRange(2 * i + 2, pts.size)
                    changed = true
                    break
                }
            }
        }
        return pts
    }

    fun contains(r: DoubleArray, x: Double, y: Double, n: Int = count(r)): Boolean {
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun centroid(r: DoubleArray, n: Int = count(r)): DoubleArray {
        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) { sx += r[2 * i]; sy += r[2 * i + 1] }
        return doubleArrayOf(sx / n, sy / n)
    }

    /** Ear-clipping triangulation of a counter-clockwise simple ring. */
    fun triangulate(r: DoubleArray, n: Int, emit: (Int, Int, Int) -> Unit) {
        if (n > 200) { for (i in 1 until n - 1) emit(0, i, i + 1); return }
        val idx = ArrayList<Int>(n).apply { for (i in 0 until n) add(i) }
        var guard = n * n
        while (idx.size > 3 && guard-- > 0) {
            var clipped = false
            for (k in idx.indices) {
                val a = idx[(k + idx.size - 1) % idx.size]; val b = idx[k]; val c = idx[(k + 1) % idx.size]
                val cross = (r[2 * b] - r[2 * a]) * (r[2 * c + 1] - r[2 * a + 1]) - (r[2 * b + 1] - r[2 * a + 1]) * (r[2 * c] - r[2 * a])
                if (cross <= 0) continue
                var blocked = false
                for (m in idx) {
                    if (m == a || m == b || m == c) continue
                    if (inTri(r, m, a, b, c)) { blocked = true; break }
                }
                if (blocked) continue
                emit(a, b, c); idx.removeAt(k); clipped = true; break
            }
            if (!clipped) break
        }
        if (idx.size >= 3) for (i in 1 until idx.size - 1) emit(idx[0], idx[i], idx[i + 1])
    }

    private fun inTri(r: DoubleArray, p: Int, a: Int, b: Int, c: Int): Boolean {
        val px = r[2 * p]; val py = r[2 * p + 1]
        fun s(i: Int, j: Int) = (r[2 * j] - r[2 * i]) * (py - r[2 * i + 1]) - (r[2 * j + 1] - r[2 * i + 1]) * (px - r[2 * i])
        val s1 = s(a, b); val s2 = s(b, c); val s3 = s(c, a)
        return s1 >= 0 && s2 >= 0 && s3 >= 0
    }

    /** Offset a polyline sideways by [d] metres (left positive). */
    fun offset(xy: DoubleArray, d: Double): DoubleArray {
        val n = xy.size / 2
        val out = DoubleArray(xy.size)
        for (i in 0 until n) {
            val a = maxOf(0, i - 1); val b = minOf(n - 1, i + 1)
            val dx = xy[2 * b] - xy[2 * a]; val dy = xy[2 * b + 1] - xy[2 * a + 1]
            val l = hypot(dx, dy).coerceAtLeast(1e-9)
            out[2 * i] = xy[2 * i] - dy / l * d
            out[2 * i + 1] = xy[2 * i + 1] + dx / l * d
        }
        return out
    }

    fun bbox(rings: List<DoubleArray>): DoubleArray {
        var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
        for (r in rings) { var i = 0; while (i < r.size) { a = minOf(a, r[i]); c = maxOf(c, r[i]); b = minOf(b, r[i + 1]); d = maxOf(d, r[i + 1]); i += 2 } }
        return doubleArrayOf(a, b, c, d)
    }
}
