package com.bike.trainer.route

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Free, key-less elevation tiles (AWS Open Data "Terrarium" DEM) used to build the
 * stylized 3D scenery. Tiles are cached on disk and shared between routes, so a
 * route that has been "stylized" (all its corridor tiles downloaded) rides fully
 * offline.
 */
object TerrainTiles {
    /** z12 ≈ 25–38 m per pixel: plenty for a low-poly look. */
    const val ZOOM = 12
    private const val TILE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png"

    /** Scenery reaches this far either side of the road. */
    private const val CORRIDOR_METERS = 3500.0
    private const val EARTH_RADIUS = 6_371_000.0

    /** Rough size of one cached tile, for the download estimate. */
    const val APPROX_TILE_BYTES = 110_000L

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun root(context: Context): File = File(context.filesDir, "terrain")

    fun tileFile(context: Context, x: Int, y: Int): File =
        File(root(context), "$ZOOM/${x}_$y.png")

    fun tileX(lon: Double): Double = (lon + 180.0) / 360.0 * (1 shl ZOOM)

    fun tileY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl ZOOM)
    }

    /** Every tile the scenery around [route] can touch. */
    fun corridorTiles(route: Route): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        if (route.points.isEmpty()) return out
        val dLat = CORRIDOR_METERS / EARTH_RADIUS * 180.0 / PI
        var d = 0.0
        while (true) {
            val p = route.pointAt(d)
            val dLon = dLat / cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)
            val x0 = floor(tileX(p.lon - dLon)).toInt()
            val x1 = floor(tileX(p.lon + dLon)).toInt()
            val y0 = floor(tileY(p.lat + dLat)).toInt()
            val y1 = floor(tileY(p.lat - dLat)).toInt()
            for (x in x0..x1) for (y in y0..y1) out.add(x to y)
            if (d >= route.totalDistance) break
            d = (d + 500.0).coerceAtMost(route.totalDistance)
        }
        return out
    }

    fun missingTiles(context: Context, route: Route): List<Pair<Int, Int>> =
        corridorTiles(route).filterNot { (x, y) -> tileFile(context, x, y).exists() }

    /** Download one tile to disk (blocking). Returns false on any failure. */
    fun fetch(context: Context, x: Int, y: Int): Boolean {
        val dest = tileFile(context, x, y)
        if (dest.exists()) return true
        return runCatching {
            val req = Request.Builder().url(TILE_URL.format(ZOOM, x, y)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                dest.parentFile?.mkdirs()
                val tmp = File(dest.path + ".part")
                tmp.writeBytes(bytes)
                tmp.renameTo(dest)
            }
        }.getOrDefault(false)
    }

    data class Progress(val done: Int, val total: Int, val failed: Int)

    /** Download all missing corridor tiles for [route]; returns the failure count. */
    suspend fun stylize(context: Context, route: Route, onProgress: (Progress) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val todo = missingTiles(context, route)
            var failed = 0
            todo.forEachIndexed { i, (x, y) ->
                coroutineContext.ensureActive()
                if (!fetch(context, x, y)) failed++
                withContext(Dispatchers.Main) { onProgress(Progress(i + 1, todo.size, failed)) }
            }
            failed
        }

    /** Decode a cached tile into metres (row-major 256×256), or null if absent/bad. */
    fun decode(context: Context, x: Int, y: Int): FloatArray? {
        val f = tileFile(context, x, y)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        if (bmp.width != 256 || bmp.height != 256) { bmp.recycle(); return null }
        val px = IntArray(256 * 256)
        bmp.getPixels(px, 0, 256, 0, 0, 256, 256)
        bmp.recycle()
        return FloatArray(px.size) { i ->
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 256f + g + b / 256f) - 32768f
        }
    }
}
