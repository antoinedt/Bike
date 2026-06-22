package com.bike.trainer.route

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Downloads Street View **Static** API frames along a route and writes a
 * [StreetViewManifest], so the ride can play cached images instead of the live
 * (flickery, online) panorama.
 *
 * To avoid paying for points with no coverage, each point is first checked
 * against the free metadata endpoint; the image is only fetched when status==OK.
 */
object StreetViewPrefetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** ~USD per Street View Static image (rough, for the in-app estimate only). */
    const val USD_PER_IMAGE = 0.007

    data class Progress(val done: Int, val total: Int, val withImage: Int)

    sealed interface Result {
        data class Success(val manifest: StreetViewManifest) : Result
        data class Error(val message: String) : Result
    }

    /** Number of sampled points for a route at [spacing] metres. */
    fun sampleCount(totalDistance: Double, spacing: Double): Int =
        if (spacing <= 0) 1 else (totalDistance / spacing).toInt().coerceAtLeast(0) + 1

    suspend fun prefetch(
        context: Context,
        route: Route,
        routeId: String,
        spacingMeters: Double,
        apiKey: String,
        reuseExisting: Boolean = true,
        onProgress: (Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Error("No Google Maps API key in this build")
        val dir = StreetViewCache.routeDir(context, routeId)

        // Frames already on disk we can reuse (same route, file present). When
        // re-fetching at a finer spacing, points that coincide with the old grid
        // are reused; only the genuinely new in-between points are downloaded.
        val previous = if (reuseExisting) {
            StreetViewCache.load(context, routeId)?.takeIf {
                it.routeFingerprint.isEmpty() || it.routeFingerprint == StreetViewCache.fingerprint(route)
            }
        } else null
        val reusable = previous?.samples?.filter { it.file != null && File(dir, it.file).exists() } ?: emptyList()
        val reuseRadius = spacingMeters * 0.5
        if (previous == null) dir.listFiles()?.forEach { it.delete() }

        val n = sampleCount(route.totalDistance, spacingMeters)
        val samples = ArrayList<StreetViewSample>(n)
        val referenced = HashSet<String>()
        var withImage = 0
        try {
            for (i in 0 until n) {
                ensureActive()
                val dist = (i * spacingMeters).coerceAtMost(route.totalDistance)
                val p = route.pointAt(dist)
                val headingDeg = Math.toDegrees(p.heading)

                val nearby = reusable.minByOrNull { abs(it.distance - dist) }
                    ?.takeIf { abs(it.distance - dist) <= reuseRadius }
                val fileName: String? = when {
                    nearby?.file != null -> nearby.file
                    hasCoverage(p.lat, p.lon, apiKey) -> {
                        val dest = freshFile(dir)
                        if (downloadImage(p.lat, p.lon, headingDeg, apiKey, dest)) dest.name
                        else { dest.delete(); null }
                    }
                    else -> null
                }
                if (fileName != null) {
                    withImage++
                    referenced.add(fileName)
                }
                samples.add(StreetViewSample(distance = dist, file = fileName))
                onProgress(Progress(done = i + 1, total = n, withImage = withImage))
            }
        } catch (e: Exception) {
            return@withContext Result.Error(e.message ?: "Prefetch failed")
        }

        // Drop any old frames the new manifest no longer references.
        dir.listFiles()?.forEach { f ->
            if (f.name != "manifest.json" && f.name !in referenced) f.delete()
        }

        if (withImage == 0) {
            return@withContext Result.Error("No Street View coverage found along this route")
        }
        val manifest = StreetViewManifest(
            routeId = routeId,
            spacingMeters = spacingMeters,
            totalDistance = route.totalDistance,
            samples = samples,
            routeFingerprint = StreetViewCache.fingerprint(route),
        )
        StreetViewCache.save(context, manifest)
        Result.Success(manifest)
    }

    /** A cache filename that doesn't collide with frames kept from a previous fetch. */
    private fun freshFile(dir: File): File {
        var n = 0
        while (true) {
            val f = File(dir, "f%06d.jpg".format(n))
            if (!f.exists()) return f
            n++
        }
    }

    private fun hasCoverage(lat: Double, lon: Double, key: String): Boolean = runCatching {
        val url = "https://maps.googleapis.com/maps/api/streetview/metadata" +
            "?location=$lat,$lon&source=outdoor&key=$key"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string() ?: return false
            JSONObject(body).optString("status") == "OK"
        }
    }.getOrDefault(false)

    private fun downloadImage(
        lat: Double,
        lon: Double,
        headingDeg: Double,
        key: String,
        dest: File,
    ): Boolean = runCatching {
        val url = "https://maps.googleapis.com/maps/api/streetview" +
            "?size=640x480&location=$lat,$lon&heading=$headingDeg" +
            "&fov=85&pitch=0&source=outdoor&return_error_code=true&key=$key"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        dest.length() > 0
    }.getOrDefault(false)
}
