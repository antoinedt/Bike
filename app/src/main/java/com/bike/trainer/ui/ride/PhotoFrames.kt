package com.bike.trainer.ui.ride

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.Matrix
import com.bike.trainer.route.LocalProjection
import com.bike.trainer.route.Route
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.route.StreetViewManifest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The prefetched Street View frames of a route, used as photo projectors over the
 * 3D scenery: each frame is a camera at the spot Google took it (≈2.5 m up, facing
 * along the route, 85° horizontal field of view, 4:3) whose image is projected
 * onto the terrain / buildings / road. Frames are decoded ahead on a worker thread.
 */
class PhotoFrames(context: Context, route: Route, manifest: StreetViewManifest) {

    class Frame(val distance: Double, val file: File, val x: Double, val y: Double, val heading: Double)

    val frames: List<Frame>
    private val bitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    // Two workers: painting a frame not prepared by "Stylize" takes a moment.
    private val loader = Executors.newFixedThreadPool(2) { r -> Thread(r, "sv-frames").apply { isDaemon = true } }
    @Volatile private var released = false

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
        }.sortedBy { it.distance }
    }

    /** Indices of the frames just behind and just ahead of [d] (null = none usable). */
    fun around(d: Double): Pair<Int?, Int?> {
        if (frames.isEmpty()) return null to null
        var lo = 0
        var hi = frames.size - 1
        var a = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid].distance <= d) { a = mid; lo = mid + 1 } else hi = mid - 1
        }
        val b = a + 1
        val ia = a.takeIf { it >= 0 && d - frames[it].distance < MAX_GAP }
        val ib = b.takeIf { it < frames.size && frames[it].distance - d < MAX_GAP }
        return ia to ib
    }

    /** Decoded image for frame [i], or null while it's still loading (then queued). */
    fun bitmap(i: Int): Bitmap? {
        bitmaps[i]?.let { return it }
        request(i)
        return null
    }

    /** Queue decoding of frame [i]; also drop images far from the current position. */
    fun request(i: Int) {
        if (i !in frames.indices || released || bitmaps.containsKey(i) || !loading.add(i)) return
        runCatching {
            loader.execute {
                try {
                    if (!released) painted(frames[i].file)?.let { bitmaps[i] = it }
                } finally {
                    loading.remove(i)
                }
            }
        }.onFailure { loading.remove(i) }
    }

    fun trim(keepFrom: Int, keepTo: Int) {
        bitmaps.keys.filter { it < keepFrom || it > keepTo }.forEach { k -> bitmaps.remove(k)?.recycle() }
    }

    /**
     * View-projection matrix of frame [i]'s camera in GL space (X = east, Y = up,
     * Z = -north), with its eye at [groundY] + camera height.
     */
    fun matrix(i: Int, groundY: Double, out: FloatArray) {
        val f = frames[i]
        val ey = (groundY + CAMERA_HEIGHT).toFloat()
        val ex = f.x.toFloat()
        val ez = (-f.y).toFloat()
        val view = FloatArray(16)
        val proj = FloatArray(16)
        Matrix.setLookAtM(
            view, 0, ex, ey, ez,
            ex + sin(f.heading).toFloat(), ey, ez - cos(f.heading).toFloat(),
            0f, 1f, 0f,
        )
        Matrix.perspectiveM(proj, 0, VFOV_DEG, ASPECT, 0.5f, 6000f)
        Matrix.multiplyMM(out, 0, proj, 0, view, 0)
    }

    fun release() {
        released = true
        loader.shutdownNow()
        // Not recycled here: the GL thread may still be uploading one; GC frees them.
        bitmaps.clear()
    }

    companion object {
        /** Where the painted (illustrated) version of a cached frame is kept. */
        fun paintedFile(original: File): File =
            File(File(original.parentFile, "painted"), original.nameWithoutExtension + ".jpg")

        /**
         * The painted version of a Street View frame: from disk if already made,
         * otherwise painted now (PaintFilter) and saved for next time.
         */
        fun painted(original: File): Bitmap? {
            val cached = paintedFile(original)
            if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return it }
            val src = BitmapFactory.decodeFile(original.path) ?: return null
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            src.recycle()
            val out = Bitmap.createBitmap(PaintFilter.apply(px, w, h), w, h, Bitmap.Config.ARGB_8888)
            runCatching {
                cached.parentFile?.mkdirs()
                val tmp = File(cached.path + ".part")
                tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                tmp.renameTo(cached)
            }
            return out
        }

        /** Frames of [manifest] that haven't been painted yet. */
        fun unpainted(context: Context, manifest: StreetViewManifest): List<File> {
            val dir = StreetViewCache.routeDir(context, manifest.routeId)
            return manifest.samples.mapNotNull { s -> s.file?.let { File(dir, it) } }
                .filter { it.exists() && !paintedFile(it).exists() }
        }

        /** Street View car camera height above the road. */
        const val CAMERA_HEIGHT = 2.5
        /** A frame further than this from the rider isn't used (coverage gap). */
        private const val MAX_GAP = 60.0
        private const val ASPECT = 640f / 480f
        /** Vertical FOV of the 85°-horizontal, 4:3 frames requested by the prefetcher. */
        private val VFOV_DEG = Math.toDegrees(2 * atan(tan(Math.toRadians(85.0 / 2)) / ASPECT)).toFloat()
    }
}
