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
        onProgress: (Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Error("No Google Maps API key in this build")
        val dir = StreetViewCache.routeDir(context, routeId)
        dir.listFiles()?.forEach { it.delete() }

        val n = sampleCount(route.totalDistance, spacingMeters)
        val samples = ArrayList<StreetViewSample>(n)
        var withImage = 0
        try {
            for (i in 0 until n) {
                ensureActive()
                val dist = (i * spacingMeters).coerceAtMost(route.totalDistance)
                val p = route.pointAt(dist)
                val headingDeg = Math.toDegrees(p.heading)
                val fileName = "%05d.jpg".format(i)
                val saved = if (hasCoverage(p.lat, p.lon, apiKey)) {
                    downloadImage(p.lat, p.lon, headingDeg, apiKey, File(dir, fileName))
                } else false
                if (saved) withImage++
                samples.add(StreetViewSample(distance = dist, file = if (saved) fileName else null))
                onProgress(Progress(done = i + 1, total = n, withImage = withImage))
            }
        } catch (e: Exception) {
            return@withContext Result.Error(e.message ?: "Prefetch failed")
        }

        if (withImage == 0) {
            return@withContext Result.Error("No Street View coverage found along this route")
        }
        val manifest = StreetViewManifest(routeId, spacingMeters, route.totalDistance, samples)
        StreetViewCache.save(context, manifest)
        Result.Success(manifest)
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
