package com.bike.trainer.ui.ride

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.Matrix
import com.bike.trainer.route.LocalProjection
import com.bike.trainer.route.Route
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.route.StreetViewManifest
import java.io.File
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Colour sampler built from a route's prefetched Street View frames. Each frame is
 * a camera at the spot Google took it (≈2.5 m up, facing along the route, 85°
 * horizontal FOV, 4:3); the 3D scenery asks "what colour is the real world at this
 * point?" and gets the averaged photo colour of the best frame seeing it, slightly
 * posterised so the result reads as an illustration rather than a photo.
 * Only used from the world-builder thread.
 */
class PhotoFrames(context: Context, route: Route, manifest: StreetViewManifest) {

    private class Frame(val distance: Double, val file: File, val x: Double, val y: Double, val heading: Double) {
        var ground = Double.NaN
        val matrix = FloatArray(16)
    }

    private val frames: List<Frame>
    private val buckets = HashMap<Long, MutableList<Int>>()
    private val cache = object : LinkedHashMap<Int, Bitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap?>?): Boolean {
            if (size > CACHE_FRAMES) { eldest?.value?.recycle(); return true }
            return false
        }
    }
    private val vec = FloatArray(4)
    private val clip = FloatArray(4)
    private val out = FloatArray(2)

    init {
        val proj = LocalProjection(route)
        val dir = StreetViewCache.routeDir(context, manifest.routeId)
        frames = manifest.samples.mapNotNull { s ->
            val f = File(dir, s.file ?: return@mapNotNull null)
            if (!f.exists()) return@mapNotNull null
            val p = route.pointAt(s.distance)
            val x = if (s.lon != null) proj.x(s.lon) else p.x
            val y = if (s.lat != null) proj.y(s.lat) else p.y
            // The image was requested facing the route heading at that distance.
            Frame(s.distance, f, x, y, p.heading)
        }
        frames.forEachIndexed { i, f -> buckets.getOrPut(key(f.x, f.y)) { ArrayList() }.add(i) }
    }

    val isEmpty: Boolean get() = frames.isEmpty()

    /** Place the frame cameras on the given ground (call once per world window). */
    fun placeOn(ground: (Double, Double) -> Double?) {
        val view = FloatArray(16)
        val proj = FloatArray(16)
        Matrix.perspectiveM(proj, 0, VFOV_DEG, ASPECT, 0.5f, 2000f)
        for (f in frames) {
            val g = ground(f.x, f.y) ?: continue
            if (g == f.ground) continue
            f.ground = g
            val ey = (g + CAMERA_HEIGHT).toFloat()
            val ex = f.x.toFloat()
            val ez = (-f.y).toFloat()
            Matrix.setLookAtM(
                view, 0, ex, ey, ez,
                ex + sin(f.heading).toFloat(), ey, ez - cos(f.heading).toFloat(),
                0f, 1f, 0f,
            )
            Matrix.multiplyMM(f.matrix, 0, proj, 0, view, 0)
        }
    }

    /**
     * Average real colour of a small surface patch: centre ([x], [y] north, [h] up)
     * spanning [halfW] metres along ([tx], [ty]) and [halfH] metres vertically,
     * facing ([nx], [ny]) (zero normal = facing up, e.g. the road). Null when no
     * nearby frame sees it.
     */
    fun colorAt(
        x: Double, y: Double, h: Double,
        nx: Double, ny: Double,
        tx: Double, ty: Double, halfW: Double, halfH: Double,
    ): Int? {
        var best = -1
        var bestScore = Double.MAX_VALUE
        val bx = floor(x / BUCKET).toInt()
        val by = floor(y / BUCKET).toInt()
        for (i in bx - 1..bx + 1) for (j in by - 1..by + 1) {
            buckets[HeightField.key(i, j)]?.forEach { k ->
                val f = frames[k]
                if (f.ground.isNaN()) return@forEach
                val dx = f.x - x; val dy = f.y - y
                val d = hypot(dx, dy)
                if (d > MAX_RANGE || d < 1.0) return@forEach
                // Facing: walls must face the camera; a frontal view gives truer colours.
                val facing = if (nx == 0.0 && ny == 0.0) 1.0 else (dx * nx + dy * ny) / d
                if (facing < 0.15) return@forEach
                if (!project(f, x, y, h)) return@forEach
                if (out[0] !in 0.03f..0.97f || out[1] !in 0.03f..0.97f) return@forEach
                val score = d / facing
                if (score < bestScore) { bestScore = score; best = k }
            }
        }
        if (best < 0) return null
        val f = frames[best]
        val bmp = bitmap(best) ?: return null
        // Average a 4×4 grid across the patch as the photo sees it.
        var r = 0; var g = 0; var b = 0; var n = 0
        for (u in 0..3) for (v in 0..3) {
            val su = (u / 3.0 - 0.5) * 2 * halfW * 0.8
            val sv = (v / 3.0 - 0.5) * 2 * halfH * 0.8
            if (!project(f, x + tx * su, y + ty * su, h + sv)) continue
            val px = (out[0] * bmp.width).toInt()
            val py = ((1f - out[1]) * bmp.height).toInt()
            if (px !in 0 until bmp.width || py !in 0 until bmp.height) continue
            val c = bmp.getPixel(px, py)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return null
        return stylize(r / n, g / n, b / n)
    }

    /** Project a local point into frame [f]: out[0..1] = image uv (0..1, v up). */
    private fun project(f: Frame, x: Double, y: Double, h: Double): Boolean {
        vec[0] = x.toFloat(); vec[1] = h.toFloat(); vec[2] = (-y).toFloat(); vec[3] = 1f
        Matrix.multiplyMV(clip, 0, f.matrix, 0, vec, 0)
        if (clip[3] <= 0.1f) return false
        out[0] = clip[0] / clip[3] * 0.5f + 0.5f
        out[1] = clip[1] / clip[3] * 0.5f + 0.5f
        return true
    }

    private fun bitmap(i: Int): Bitmap? {
        if (cache.containsKey(i)) return cache[i]
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = runCatching { BitmapFactory.decodeFile(frames[i].file.path, opts) }.getOrNull()
        cache[i] = bmp
        return bmp
    }

    fun release() {
        cache.values.forEach { it?.recycle() }
        cache.clear()
    }

    private fun key(x: Double, y: Double) = HeightField.key(floor(x / BUCKET).toInt(), floor(y / BUCKET).toInt())

    companion object {
        /** Street View car camera height above the road. */
        const val CAMERA_HEIGHT = 2.5
        private const val BUCKET = 50.0
        /** Only trust frames this close: further away the photo detail is mush. */
        private const val MAX_RANGE = 60.0
        private const val CACHE_FRAMES = 40
        private const val ASPECT = 640f / 480f
        /** Vertical FOV of the 85°-horizontal, 4:3 frames requested by the prefetcher. */
        private val VFOV_DEG = Math.toDegrees(2 * atan(tan(Math.toRadians(85.0 / 2)) / ASPECT)).toFloat()
        private const val LEVELS = 10.0

        /** Illustration look: a touch more saturation, then posterise. */
        fun stylize(r: Int, g: Int, b: Int): Int {
            val l = (r + g + b) / 3.0
            fun ch(c: Int): Int {
                val boosted = (l + (c - l) * 1.25).coerceIn(0.0, 255.0)
                return ((boosted / 255.0 * LEVELS).roundToInt() / LEVELS * 255.0).roundToInt().coerceIn(0, 255)
            }
            return Color.rgb(ch(r), ch(g), ch(b))
        }
    }
}
