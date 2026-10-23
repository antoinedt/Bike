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
import kotlin.math.min
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

    /** How much of a route's world is built: map sections downloaded out of the total. */
    class Status(val done: Int, val total: Int) {
        val complete get() = done >= total
    }

    /**
     * The built world for this exact route (and builder version), or null. A world
     * can be partial when some map sections couldn't be downloaded: it still rides
     * (those stretches have terrain only) and the next build fetches the rest.
     */
    fun status(context: Context, route: Route): Status? {
        val id = route.id ?: return null
        val d = dir(context, id)
        if (!File(d, "world.json").exists()) return null
        val parts = File(d, "stamp.txt").takeIf { it.exists() }?.readText()?.split("#") ?: return null
        if (parts[0] != stamp(route)) return null
        val counts = parts.getOrNull(1)?.split("/")
        val total = counts?.getOrNull(1)?.toIntOrNull() ?: return Status(1, 1)
        return Status(counts[0].toIntOrNull() ?: 0, total)
    }

    fun isBuilt(context: Context, route: Route): Boolean = status(context, route) != null

    class Progress(val step: String, val fraction: Float)

    /** Outcome of a build: null error = world written ([status] says how complete). */
    class Result(val error: String?, val status: Status?)

    /**
     * Build (or finish) the world. Map sections already downloaded are reused; any
     * that still fail are skipped so the rest can be built and ridden now.
     */
    suspend fun build(context: Context, route: Route, onProgress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        val id = route.id ?: return@withContext Result("Only GPX routes have a real place to build", null)
        if (route.points.size < 2) return@withContext Result("Route is empty", null)
        val out = dir(context, id).apply { mkdirs() }
        val osmDir = File(out, "osm").apply { mkdirs() }
        val fp = StreetViewCache.fingerprint(route)

        // 1. Map data, section by section (cached per route fingerprint).
        val sections = ((route.totalDistance / SECTION_METERS).toInt() + 1).coerceAtLeast(1)
        val jsons = ArrayList<String>()
        var done = 0
        var downloaded = 0
        for (s in 0 until sections) {
            coroutineContext.ensureActive()
            val label = "Map data ${s + 1}/$sections"
            post(onProgress, Progress(label, 0.6f * s / sections))
            val key = "$fp|$s|$CORRIDOR_METERS"
            val keyFile = File(osmDir, "section_$s.key")
            val cached = if (keyFile.exists() && keyFile.readText().startsWith("$key|")) {
                val n = keyFile.readText().substringAfterLast('|').toIntOrNull() ?: 0
                (0 until n).map { File(osmDir, "section_${s}_$it.json") }.takeIf { fs -> fs.isNotEmpty() && fs.all { it.exists() } }
            } else null
            if (cached != null) {
                cached.forEach { jsons.add(it.readText()) }
                done++
                continue
            }
            // Be gentle with the free servers between downloads.
            if (downloaded > 0) delay(2500)
            val parts = download(route, s * SECTION_METERS, min((s + 1) * SECTION_METERS, route.totalDistance), 0) { msg ->
                post(onProgress, Progress("$label — $msg", 0.6f * s / sections))
            }
            downloaded++
            if (parts == null) continue // skipped for now; the next build retries it
            parts.forEachIndexed { k, text -> File(osmDir, "section_${s}_$k.json").writeText(text) }
            keyFile.writeText("$key|${parts.size}")
            jsons.addAll(parts)
            done++
        }
        if (done == 0) {
            return@withContext Result("Couldn't download any map data (servers busy or offline) — try again later", status(context, route))
        }

        // 2. Build with what we have.
        post(onProgress, Progress("Building the 3D world…", 0.65f))
        val geo = Geo(route.points[0].lat, route.points[0].lon)
        val osm = OsmData.merge(jsons, geo)
        jsons.clear()
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
            return@withContext Result("World build failed: ${e.message}", status(context, route))
        }

        // 3. Swap in the new world.
        out.listFiles()?.forEach { f -> if (f.isFile) f.delete() }
        tmp.listFiles()?.forEach { f -> f.renameTo(File(out, f.name)) }
        tmp.deleteRecursively()
        File(out, "stamp.txt").writeText("${stamp(route)}#$done/$sections")
        post(onProgress, Progress("Done", 1f))
        Result(null, Status(done, sections))
    }

    /**
     * Map data for the route between [d0] and [d1]. A range the server finds too
     * big is split in two (up to 3 times). Null if it couldn't be downloaded.
     */
    private suspend fun download(route: Route, d0: Double, d1: Double, depth: Int, note: (String) -> Unit): List<String>? {
        return when (val r = overpass(query(route, d0, d1), note)) {
            is Answer.Ok -> listOf(r.text)
            Answer.TooBig -> {
                if (depth >= 3) return null
                note("large area, splitting")
                val mid = (d0 + d1) / 2
                val a = download(route, d0, mid, depth + 1, note) ?: return null
                delay(2000)
                val b = download(route, mid, d1, depth + 1, note) ?: return null
                a + b
            }
            Answer.Failed -> null
        }
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

    private sealed interface Answer {
        class Ok(val text: String) : Answer
        object TooBig : Answer
        object Failed : Answer
    }

    /**
     * One Overpass request with patient retries across mirrors: busy servers (429 /
     * 503) get increasingly long pauses; a server-side timeout or out-of-memory
     * means the area is too big for one request.
     */
    private suspend fun overpass(q: String, note: (String) -> Unit): Answer {
        val waits = longArrayOf(15_000, 40_000, 90_000)
        for (round in 0..waits.size) {
            var tooBig = 0
            for (url in ENDPOINTS) {
                coroutineContext.ensureActive()
                val (code, text) = runCatching {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "VibeBike/1.0 (indoor cycling app)")
                        .post(FormBody.Builder().add("data", q).build())
                        .build()
                    http.newCall(req).execute().use { resp -> resp.code to resp.body?.string() }
                }.getOrElse { -1 to null }
                if (code == 200 && text != null && text.contains("\"elements\"")) {
                    // A runtime error remark means the answer was cut short.
                    if (text.contains("\"remark\"") && text.contains("runtime error")) tooBig++ else return Answer.Ok(text)
                } else if (code == 504 || (text != null && text.contains("runtime error"))) {
                    tooBig++
                }
            }
            if (tooBig > 0) return Answer.TooBig
            if (round < waits.size) {
                note("servers busy, retrying in ${waits[round] / 1000} s")
                delay(waits[round])
            }
        }
        return Answer.Failed
    }
}
