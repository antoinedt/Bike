package com.bike.trainer.ui.world

import android.content.Context
import com.bike.trainer.route.Route
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.ui.ride.HeightField
import com.bike.trainer.world.Geo
import com.bike.trainer.world.HeightSource
import com.bike.trainer.world.OsmData
import com.bike.trainer.world.SceneBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * "Build world": prepares a route's offline 3D world on the phone.
 *
 * 1. Downloads the raw OpenStreetMap data of the route corridor from the free
 *    Overpass API (in sections, cached so a rebuild needs no network);
 * 2. Runs [SceneBuilder] with the terrain heights the app already uses;
 * 3. Stores the result in filesDir/worlds/<route>/ for the ride (fully offline).
 */
object WorldStore {
    private const val SECTION_METERS = 1500.0
    private const val CORRIDOR_METERS = 320
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    private const val KEYS =
        "^(building|building:part|highway|railway|waterway|landuse|natural|leisure|amenity|barrier|emergency|public_transport|area:highway|water)$"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    fun dir(context: Context, routeId: String): File =
        File(context.filesDir, "worlds/" + StreetViewCache.sanitize(routeId))

    private fun stamp(route: Route) = "${SceneBuilder.VERSION}|${StreetViewCache.fingerprint(route)}"

    /** True when a world matching this exact route (and builder version) is on disk. */
    fun isBuilt(context: Context, route: Route): Boolean {
        val id = route.id ?: return false
        val d = dir(context, id)
        return File(d, "world.json").exists() && File(d, "stamp.txt").takeIf { it.exists() }?.readText() == stamp(route)
    }

    class Progress(val step: String, val fraction: Float)

    /** Build (or rebuild) the world; returns an error message, or null on success. */
    suspend fun build(context: Context, route: Route, onProgress: (Progress) -> Unit): String? = withContext(Dispatchers.IO) {
        val id = route.id ?: return@withContext "Only GPX routes have a real place to build"
        if (route.points.size < 2) return@withContext "Route is empty"
        val out = dir(context, id).apply { mkdirs() }
        val osmDir = File(out, "osm").apply { mkdirs() }
        val fp = StreetViewCache.fingerprint(route)

        // 1. Map data, section by section (cached per route fingerprint).
        val sections = ((route.totalDistance / SECTION_METERS).toInt() + 1).coerceAtLeast(1)
        val jsons = ArrayList<String>()
        for (s in 0 until sections) {
            coroutineContext.ensureActive()
            post(onProgress, Progress("Downloading map data ${s + 1}/$sections", 0.6f * s / sections))
            val cache = File(osmDir, "section_$s.json")
            val cacheKey = File(osmDir, "section_$s.key")
            val key = "$fp|$s|$CORRIDOR_METERS"
            if (cache.exists() && cacheKey.exists() && cacheKey.readText() == key) {
                jsons.add(cache.readText()); continue
            }
            val text = overpass(query(route, s * SECTION_METERS, (s + 1) * SECTION_METERS))
                ?: return@withContext "Couldn't download map data (Overpass busy or offline) — try again in a minute"
            cache.writeText(text); cacheKey.writeText(key)
            jsons.add(text)
        }

        // 2. Build.
        post(onProgress, Progress("Building the 3D world…", 0.65f))
        val geo = Geo(route.points[0].lat, route.points[0].lon)
        val osm = OsmData.merge(jsons, geo)
        val field = HeightField(context, route, allowNetwork = true)
        val heights = HeightSource { x, y -> field.height(x, y) }
        val tmp = File(out, "build.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            withContext(Dispatchers.Default) {
                SceneBuilder(route, osm, heights).build({ name, bytes -> File(tmp, name).writeBytes(bytes) }) { step ->
                    post(onProgress, Progress("Building: $step…", 0.75f))
                }
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return@withContext "World build failed: ${e.message}"
        }

        // 3. Swap in the new world.
        out.listFiles()?.forEach { f -> if (f.isFile) f.delete() }
        tmp.listFiles()?.forEach { f -> f.renameTo(File(out, f.name)) }
        tmp.deleteRecursively()
        File(out, "stamp.txt").writeText(stamp(route))
        post(onProgress, Progress("Done", 1f))
        null
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun post(cb: (Progress) -> Unit, p: Progress) {
        main.post { cb(p) }
    }

    /** Overpass QL: everything we draw within the corridor around this route section. */
    private fun query(route: Route, d0: Double, d1: Double): String {
        val coords = StringBuilder()
        var d = d0
        while (true) {
            val p = route.pointAt(d.coerceAtMost(route.totalDistance))
            if (coords.isNotEmpty()) coords.append(',')
            coords.append(String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon))
            if (d >= d1 || d >= route.totalDistance) break
            d += 80.0
        }
        return "[out:json][timeout:150];" +
            "nwr(around:$CORRIDOR_METERS,$coords)[~\"$KEYS\"~\".\"];" +
            "out geom;"
    }

    private suspend fun overpass(q: String): String? {
        var wait = 2000L
        repeat(3) { attempt ->
            for (url in ENDPOINTS) {
                coroutineContext.ensureActive()
                val text = runCatching {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "VibeBike/1.0 (indoor cycling app)")
                        .post(FormBody.Builder().add("data", q).build())
                        .build()
                    http.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
                }.getOrNull()
                if (text != null && text.contains("\"elements\"")) return text
            }
            if (attempt < 2) { delay(wait); wait *= 3 }
        }
        return null
    }
}
