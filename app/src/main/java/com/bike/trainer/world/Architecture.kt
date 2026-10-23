package com.bike.trainer.world

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a route is, broadly: decides the local building vocabulary. */
enum class Region { EUROPE, NORTH_AMERICA, OTHER;
    companion object {
        fun of(lat: Double, lon: Double): Region = when {
            lon < -30 && lat > 15 -> NORTH_AMERICA
            lon in -25.0..45.0 && lat in 34.0..72.0 -> EUROPE
            else -> OTHER
        }
    }
}

/** A façade material, read from the Street View photos (or guessed). */
enum class Material { PLASTER, BRICK, STONE, GLASS, CONCRETE }

/** What the photos say about a building (null fields = unknown). */
class BuildingFacts(val colour: Int?, val material: Material?)

/**
 * Architectural styles: each has its own window rhythm / proportions (drawn by the
 * façade shader from [id]), its own details (cornices, ledges, balconies …) and a
 * palette used when neither OSM nor the photos give a colour.
 */
enum class ArchStyle(val id: Int, val bay: Double, val palette: IntArray) {
    BLANK(0, 3.2, intArrayOf(0xB9B5AE, 0xA9A49C)),
    HISTORIC(1, 2.9, intArrayOf(0xEADBB0, 0xE3C489, 0xE7A983, 0xD9D3C4, 0xF1ECE2, 0xBFD3C0, 0xE9CFA0, 0xD8B9A0)),
    SLAB(2, 3.0, intArrayOf(0xD9D6CC, 0xCFD4CF, 0xE2D7C2, 0xC9CED6)),
    GLASS(3, 1.6, intArrayOf(0x7C97B5, 0x8AA3B8, 0x6F8CA8, 0x9DB0BF)),
    PLEX(4, 2.6, intArrayOf(0xA9573F, 0xB8664A, 0x9A4E3A, 0xC08A5E, 0xB07752)),
    HOUSE(5, 3.3, intArrayOf(0xF1ECE2, 0xE6D8B8, 0xCFDCE3, 0xE9CFA0, 0xD6C4AA)),
    INDUSTRIAL(6, 4.0, intArrayOf(0xBFC3C6, 0xA9B0B5, 0xC9C2B4, 0x9FA9AE)),
    CHURCH(7, 4.5, intArrayOf(0xD8CDB8, 0xC9BCA4, 0xB7A68C));

    companion object {
        /**
         * Pick a style from OSM tags, size, region and (when available) the photos.
         * [levels] may be estimated from the height.
         */
        fun classify(
            type: String, tags: Map<String, String>, height: Double, levels: Int,
            area: Double, longSide: Double, region: Region, facts: BuildingFacts?,
        ): ArchStyle {
            val t = type
            if (t in setOf("garage", "garages", "shed", "carport", "roof", "hut", "kiosk", "service", "transformer_tower")) return BLANK
            if (t in setOf("church", "cathedral", "chapel", "mosque", "synagogue", "temple") || tags["amenity"] == "place_of_worship") return CHURCH
            if (t in setOf("industrial", "warehouse", "manufacture", "hangar", "factory", "storage_tank") ||
                (t in setOf("retail", "supermarket") && area > 1500 && levels <= 2)
            ) return INDUSTRIAL
            if (facts?.material == Material.GLASS && levels >= 3) return GLASS
            if (t in setOf("house", "detached", "semidetached_house", "bungalow", "farm", "cabin", "static_caravan") ||
                (levels <= 2 && area < 250)
            ) return HOUSE
            if (t in setOf("office", "commercial") && (height >= 25 || region != Region.EUROPE)) return GLASS
            if (height >= 40) return GLASS
            return when (region) {
                Region.NORTH_AMERICA -> when {
                    levels in 2..4 && area < 900 -> PLEX
                    levels >= 8 -> if (facts?.material == Material.BRICK) SLAB else GLASS
                    else -> PLEX
                }
                Region.EUROPE -> when {
                    levels >= 8 || (levels >= 6 && longSide > 45) -> SLAB
                    facts?.material == Material.CONCRETE && levels >= 5 -> SLAB
                    else -> HISTORIC
                }
                Region.OTHER -> if (levels >= 7) SLAB else HISTORIC
            }
        }
    }
}

/** Colour helpers (sRGB ints). */
internal object Rgb {
    fun mix(a: Int, b: Int, t: Double): Int {
        fun c(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (c(16) shl 16) or (c(8) shl 8) or c(0)
    }
    fun lighter(c: Int, t: Double) = mix(c, 0xFFFFFF, t)
    fun darker(c: Int, t: Double) = mix(c, 0x000000, t)
}

/**
 * Extra architectural geometry around an extruded footprint: projecting bands
 * (cornices, ledges, parapets), balconies and galleries, chimneys. Everything is
 * emitted as flat-shaded quads in Godot space (X = east, Y = up, Z = -north).
 */
internal class Details(private val mesh: MeshData, private val edge: (Double, Double, Double, Double, Double, Double) -> Unit) {

    /**
     * A band around a CCW [ring] between heights [h0]..[h1], projecting [out] metres
     * (front, top and underside faces, with ink lines along its edges).
     */
    fun band(ring: DoubleArray, h0: Double, h1: Double, out: Double, rgb: Int) {
        val n = ring.size / 2
        val off = offsetRing(ring, out)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = ring[2 * i]; val ay = ring[2 * i + 1]; val bx = ring[2 * j]; val by = ring[2 * j + 1]
            val oax = off[2 * i]; val oay = off[2 * i + 1]; val obx = off[2 * j]; val oby = off[2 * j + 1]
            val len = hypot(bx - ax, by - ay)
            if (len < 0.05) continue
            val nx = (by - ay) / len; val nz = (bx - ax) / len
            quad(oax, oay, obx, oby, h0, h1, nx, 0.0, nz, rgb)                                     // front
            flat(ax, ay, bx, by, obx, oby, oax, oay, h1, 1.0, Rgb.lighter(rgb, 0.08))             // top
            flat(ax, ay, bx, by, obx, oby, oax, oay, h0, -1.0, Rgb.darker(rgb, 0.25))             // underside
            edge(oax, h1, oay, obx, h1, oby)
            edge(oax, h0, oay, obx, h0, oby)
        }
    }

    /** A box: centre (x, y north, h up), half sizes along the wall ([ux],[uy]), outwards ([nx],[ny]) and up. */
    fun box(x: Double, y: Double, h: Double, ux: Double, uy: Double, nx: Double, ny: Double,
            halfAlong: Double, halfOut: Double, halfUp: Double, rgb: Int, ink: Boolean = true) {
        fun p(a: Double, o: Double, u: Double) = doubleArrayOf(x + ux * a + nx * o, y + uy * a + ny * o, h + u)
        val c = arrayOf(
            p(-halfAlong, -halfOut, -halfUp), p(halfAlong, -halfOut, -halfUp), p(halfAlong, halfOut, -halfUp), p(-halfAlong, halfOut, -halfUp),
            p(-halfAlong, -halfOut, halfUp), p(halfAlong, -halfOut, halfUp), p(halfAlong, halfOut, halfUp), p(-halfAlong, halfOut, halfUp),
        )
        fun face(a: Int, b: Int, cc: Int, d: Int, fnx: Double, fny: Double, fnz: Double, col: Int) {
            val s = mesh.vertexCount
            for (k in intArrayOf(a, b, cc, d)) mesh.v(c[k][0], c[k][2], -c[k][1], fnx, fny, fnz, col)
            mesh.tri(s, s + 1, s + 2); mesh.tri(s, s + 2, s + 3)
        }
        face(3, 2, 6, 7, nx, 0.0, -ny, rgb)                                  // front (outwards)
        face(1, 0, 4, 5, -nx, 0.0, ny, Rgb.darker(rgb, 0.15))               // back
        face(4, 5, 6, 7, 0.0, 1.0, 0.0, Rgb.lighter(rgb, 0.08))             // top
        face(0, 1, 2, 3, 0.0, -1.0, 0.0, Rgb.darker(rgb, 0.3))              // bottom
        face(0, 3, 7, 4, -ux, 0.0, uy, Rgb.darker(rgb, 0.1))                // side
        face(2, 1, 5, 6, ux, 0.0, -uy, Rgb.darker(rgb, 0.1))                // side
        if (ink) {
            for ((a, b) in listOf(7 to 6, 3 to 2, 2 to 6, 3 to 7)) edge(c[a][0], c[a][2], c[a][1], c[b][0], c[b][2], c[b][1])
        }
    }

    private fun quad(ax: Double, ay: Double, bx: Double, by: Double, h0: Double, h1: Double, nx: Double, ny: Double, nz: Double, rgb: Int) {
        val s = mesh.vertexCount
        mesh.v(ax, h0, -ay, nx, ny, nz, rgb); mesh.v(bx, h0, -by, nx, ny, nz, rgb)
        mesh.v(bx, h1, -by, nx, ny, nz, rgb); mesh.v(ax, h1, -ay, nx, ny, nz, rgb)
        mesh.tri(s, s + 1, s + 2); mesh.tri(s, s + 2, s + 3)
    }

    private fun flat(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double, h: Double, ny: Double, rgb: Int) {
        val s = mesh.vertexCount
        mesh.v(ax, h, -ay, 0.0, ny, 0.0, rgb); mesh.v(bx, h, -by, 0.0, ny, 0.0, rgb)
        mesh.v(cx, h, -cy, 0.0, ny, 0.0, rgb); mesh.v(dx, h, -dy, 0.0, ny, 0.0, rgb)
        mesh.tri(s, s + 1, s + 2); mesh.tri(s, s + 2, s + 3)
    }

    companion object {
        /** Offset a CCW ring outwards by [d] (mitred, clamped at sharp corners). */
        fun offsetRing(r: DoubleArray, d: Double): DoubleArray {
            val n = r.size / 2
            val out = DoubleArray(r.size)
            for (i in 0 until n) {
                val a = (i + n - 1) % n; val c = (i + 1) % n
                fun nrm(p: Int, q: Int): DoubleArray {
                    val dx = r[2 * q] - r[2 * p]; val dy = r[2 * q + 1] - r[2 * p + 1]
                    val l = hypot(dx, dy).coerceAtLeast(1e-9)
                    return doubleArrayOf(dy / l, -dx / l)
                }
                val n1 = nrm(a, i); val n2 = nrm(i, c)
                var mx = n1[0] + n2[0]; var my = n1[1] + n2[1]
                val ml = hypot(mx, my)
                if (ml < 1e-6) { mx = n1[0]; my = n1[1] } else { mx /= ml; my /= ml }
                val cosHalf = max(0.35, mx * n2[0] + my * n2[1])
                out[2 * i] = r[2 * i] + mx * d / cosHalf
                out[2 * i + 1] = r[2 * i + 1] + my * d / cosHalf
            }
            return out
        }
    }
}

/** Levels from tags, else estimated from the height. */
internal fun estimateLevels(levelsTag: Double?, height: Double): Int =
    levelsTag?.roundToInt()?.coerceAtLeast(1) ?: max(1, (height / 3.2).roundToInt())

internal fun longestSide(r: DoubleArray): Double {
    val n = r.size / 2
    var best = 0.0
    for (i in 0 until n) { val j = (i + 1) % n; best = max(best, hypot(r[2 * j] - r[2 * i], r[2 * j + 1] - r[2 * i + 1])) }
    return best
}

internal fun clampFloor(h: Double, levels: Int) = min(4.4, max(2.7, h / max(1, levels)))

