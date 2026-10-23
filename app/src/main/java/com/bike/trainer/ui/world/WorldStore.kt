package com.bike.trainer.ui.world

import android.content.Context
import com.bike.trainer.route.Route
import com.bike.trainer.route.OsmTiles
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.ui.ride.HeightField
import com.bike.trainer.world.Geo
import com.bike.trainer.world.HeightSource
import com.bike.trainer.world.Landmarks
import com.bike.trainer.world.OsmData
import com.bike.trainer.world.PhotoAnalysis
import com.bike.trainer.world.SegFrame
import com.bike.trainer.world.SceneBuilder
import com.bike.trainer.world.TileOsm
import com.bike.trainer.world.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    /** Overpass sections: small, so each finishes quickly and progress is saved often. */
    private const val SECTION_METERS = 1000.0
    /** Earlier builds used 3 km sections; those downloads are still reused. */
    private const val LEGACY_SECTION_METERS = 3000.0
    /** Route length per bbox of the landmarks query. */
    private const val LANDMARK_SECTION_METERS = 2000.0
    private const val CORRIDOR_METERS = 320.0
    /** Map tiles are fetched this far either side of the route. */
    private const val TILE_CORRIDOR_METERS = 420.0
    private const val MAIN_SERVER = "https://overpass-api.de/api/"
    private val ENDPOINTS = listOf(
        MAIN_SERVER + "interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    /**
     * The individual points the vector tiles don't carry (trees, lamps, benches …),
     * as tag-indexed node queries — cheap for the server. Run inside a bounding box.
     */
    private const val POINT_FILTERS =
        "node[\"natural\"=\"tree\"];way[\"natural\"=\"tree_row\"];" +
            "node[\"highway\"~\"^(street_lamp|bus_stop|traffic_signals)$\"];node[\"crossing\"=\"traffic_signals\"];" +
            "node[\"amenity\"~\"^(bench|bicycle_parking|waste_basket)$\"];node[\"barrier\"=\"bollard\"];" +
            "node[\"emergency\"=\"fire_hydrant\"];node[\"leisure\"=\"picnic_table\"];"

    /** Landmark buildings / parts, with all their tags (roof shapes, types). */
    private val LANDMARK_FILTERS = listOf(
        "way[\"roof:shape\"~\"^(dome|onion|cone|pyramidal|round)$\"]",
        "rel[\"roof:shape\"~\"^(dome|onion|cone|pyramidal|round)$\"]",
        "way[\"building\"~\"^(church|cathedral|chapel|mosque|synagogue|temple)$\"]",
        "rel[\"building\"~\"^(church|cathedral|chapel|mosque|synagogue|temple)$\"]",
        "way[\"amenity\"=\"place_of_worship\"][\"building\"]",
    )

    /** z14 vector tiles within [TILE_CORRIDOR_METERS] of the route. */
    private fun corridorTiles(route: Route): List<Pair<Int, Int>> {
        val out = LinkedHashSet<Pair<Int, Int>>()
        val dLat = TILE_CORRIDOR_METERS / 111_320.0
        var d = 0.0
        while (true) {
            val p = route.pointAt(d)
            val dLon = dLat / kotlin.math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)
            for (x in kotlin.math.floor(OsmTiles.tileX(p.lon - dLon)).toInt()..kotlin.math.floor(OsmTiles.tileX(p.lon + dLon)).toInt()) {
                for (y in kotlin.math.floor(OsmTiles.tileY(p.lat + dLat)).toInt()..kotlin.math.floor(OsmTiles.tileY(p.lat - dLat)).toInt()) {
                    out.add(x to y)
                }
            }
            if (d >= route.totalDistance) break
            d = (d + 100.0).coerceAtMost(route.totalDistance)
        }
        return out.toList()
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    fun dir(context: Context, routeId: String): File =
        File(context.filesDir, "worlds/" + StreetViewCache.sanitize(routeId))

    private fun stamp(route: Route) = "${SceneBuilder.VERSION}|${StreetViewCache.fingerprint(route)}"

    /**
     * How a route's world is built: map sections downloaded out of the total, how
     * many Street View photos went into it, and whether it predates the current
     * builder (it still rides, but a rebuild looks better).
     */
    class Status(val done: Int, val total: Int, val photos: Int = 0, val outdated: Boolean = false) {
        val complete get() = done >= total
    }

    /** Prefetched Street View frames available for this route (0 = none). */
    fun availablePhotos(context: Context, route: Route): Int =
        StreetViewCache.loadFor(context, route)?.imageCount ?: 0

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
        val head = parts[0].split("|")
        if (head.getOrNull(1) != StreetViewCache.fingerprint(route)) return null
        val outdated = head[0] != SceneBuilder.VERSION.toString()
        val photos = parts.firstOrNull { it.startsWith("photos=") }?.removePrefix("photos=")?.toIntOrNull() ?: 0
        val counts = parts.getOrNull(1)?.split("/")
        val total = counts?.getOrNull(1)?.toIntOrNull() ?: return Status(1, 1, photos, outdated)
        return Status(counts[0].toIntOrNull() ?: 0, total, photos, outdated)
    }

    fun isBuilt(context: Context, route: Route): Boolean = status(context, route) != null

    class Progress(val step: String, val fraction: Float)

    /** Outcome of a build: null error = world written ([status] says how complete). */
    class Result(val error: String?, val status: Status?)

    /**
     * Build (or finish) the world. Map sections already downloaded are reused; any
     * that still fail are skipped so the rest can be built and ridden now.
     */
    suspend fun build(
        context: Context,
        route: Route,
        /** Set to stop downloading early and build the world from what has arrived. */
        stopDownloads: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        onProgress: (Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val id = route.id ?: return@withContext Result("Only GPX routes have a real place to build", null)
        if (route.points.size < 2) return@withContext Result("Route is empty", null)
        val out = dir(context, id).apply { mkdirs() }
        val osmDir = File(out, "osm").apply { mkdirs() }
        val fp = StreetViewCache.fingerprint(route)

        val geo = Geo(route.points[0].lat, route.points[0].lon)
        val notes = java.util.concurrent.ConcurrentHashMap<String, String>()
        val tiles = corridorTiles(route)
        val tileDone = java.util.concurrent.atomic.AtomicInteger(tiles.count { (x, y) -> OsmTiles.tileFile(context, x, y).exists() })

        // Points (trees, lamps, …) from Overpass in 3 km sections, cached per route.
        val sections = ((route.totalDistance / SECTION_METERS).toInt() + 1).coerceAtLeast(1)
        fun key(s: Int) = "$fp|$s|${SECTION_METERS.toInt()}|${CORRIDOR_METERS.toInt()}|points"
        fun pointsFile(s: Int): File? {
            val k = File(osmDir, "pts1k_$s.key")
            val f = File(osmDir, "pts1k_$s.json")
            if (f.exists() && k.exists() && k.readText() == key(s)) return f
            // A 3 km section from an earlier build covering this kilometre.
            val ls = (s * SECTION_METERS / LEGACY_SECTION_METERS).toInt()
            val lk = File(osmDir, "points_$ls.key")
            val lf = File(osmDir, "points_$ls.json")
            val legacyKey = "$fp|$ls|${LEGACY_SECTION_METERS.toInt()}|${CORRIDOR_METERS.toInt()}|points"
            return lf.takeIf { it.exists() && lk.exists() && lk.readText() == legacyKey }
        }
        // Landmarks (domes, spires, churches): one extra query, queued after the points.
        val landmarksKey = "$fp|landmarks1"
        fun landmarksFile(): File? = File(osmDir, "landmarks.json").takeIf {
            it.exists() && File(osmDir, "landmarks.key").let { k -> k.exists() && k.readText() == landmarksKey }
        }
        val pointsDone = java.util.concurrent.atomic.AtomicInteger((0 until sections).count { pointsFile(it) != null } + if (landmarksFile() != null) 1 else 0)
        fun report() = post(onProgress, Progress(
            "Map tiles ${tileDone.get()}/${tiles.size} · trees, furniture & landmarks ${pointsDone.get()}/${sections + 1}" +
                if (notes.isEmpty()) "" else "\n" + notes.values.joinToString("\n"),
            0.6f * (tileDone.get() + pointsDone.get()) / (tiles.size + sections + 1),
        ))
        report()

        kotlinx.coroutines.coroutineScope {
          val downloads = launch {
            // 1. Vector tiles (buildings, streets, land use, water): fast CDN, 6 at a time.
            val tileQueue = java.util.concurrent.ConcurrentLinkedQueue(tiles.filter { (x, y) -> !OsmTiles.tileFile(context, x, y).exists() })
            repeat(6) {
                launch {
                    while (true) {
                        coroutineContext.ensureActive()
                        val (x, y) = tileQueue.poll() ?: break
                        if (OsmTiles.fetch(context, x, y)) tileDone.incrementAndGet()
                        report()
                    }
                }
            }
            // 2. Points from Overpass: one light, node-only query per section, per server.
            val queue = java.util.concurrent.ConcurrentLinkedQueue((0 until sections).filter { pointsFile(it) == null } +
                if (landmarksFile() == null) listOf(sections) else emptyList())
            val tries = java.util.concurrent.ConcurrentHashMap<Int, Int>()
            for (url in ENDPOINTS) launch {
                val name = java.net.URI(url).host.removePrefix("maps.").removePrefix("overpass.")
                var failuresInARow = 0
                while (failuresInARow < 3) {
                    coroutineContext.ensureActive()
                    val s = queue.poll() ?: break
                    val isLandmarks = s == sections
                    notes[name] = if (isLandmarks) "$name: landmarks…" else "$name: trees & furniture ${s + 1}…"; report()
                    val t0 = System.currentTimeMillis()
                    val q = if (isLandmarks) landmarksQuery(route)
                    else pointsQuery(route, s * SECTION_METERS, min((s + 1) * SECTION_METERS, route.totalDistance))
                    when (val r = overpass(q, url) { msg -> notes[name] = "$name: ${s + 1} $msg"; report() }) {
                        is Answer.Ok -> {
                            if (isLandmarks) {
                                File(osmDir, "landmarks.json").writeText(r.text)
                                File(osmDir, "landmarks.key").writeText(landmarksKey)
                            } else {
                                File(osmDir, "pts1k_$s.json").writeText(r.text)
                                File(osmDir, "pts1k_$s.key").writeText(key(s))
                            }
                            pointsDone.incrementAndGet()
                            failuresInARow = 0
                            notes[name] = "$name: ${s + 1} done (${r.text.length / 1024} KB, ${(System.currentTimeMillis() - t0) / 1000}s)"; report()
                            delay(1500)
                        }
                        else -> {
                            failuresInARow++
                            if (tries.merge(s, 1, Int::plus)!! < ENDPOINTS.size + 1) queue.add(s)
                            notes[name] = "$name: busy, pausing"; report()
                            delay(20_000L * failuresInARow)
                        }
                    }
                }
                notes.remove(name); report()
            }
          }
            // "Stop & build": abort the downloads (in-flight requests too), keep going.
            while (downloads.isActive) {
                if (stopDownloads.get()) downloads.cancel()
                delay(200)
            }
        }
        coroutineContext.ensureActive()

        // Gather what we have.
        post(onProgress, Progress("Reading map data…", 0.62f))
        var osm = OsmData(emptyList(), emptyList(), emptyList())
        var tilesHere = 0
        for ((x, y) in tiles) {
            val feats = OsmTiles.load(context, x, y, TileOsm.LAYERS) ?: continue
            osm = osm.plus(TileOsm.convert(feats, x, y, OsmTiles.ZOOM, geo))
            tilesHere++
        }
        // (A legacy 3 km file can cover several sections: read each file once.)
        val pointJsons = (0 until sections).mapNotNull { pointsFile(it) }.distinct().map { it.readText() }
        if (pointJsons.isNotEmpty()) osm = osm.plus(OsmData.merge(pointJsons, geo).let { OsmData(it.points, it.ways, emptyList()) })
        val landmarks = landmarksFile()
        if (landmarks != null) {
            val lm = OsmData.parse(landmarks.readText(), geo).areas
                .filter { it.tags.containsKey("building") || it.tags.containsKey("building:part") }
            osm = Landmarks.merge(osm, lm)
        }
        val built = tilesHere + pointJsons.size + if (landmarks != null) 1 else 0
        val total = tiles.size + sections + 1
        if (tilesHere == 0) {
            return@withContext Result("Couldn't download the map tiles (offline?) — try again later", status(context, route))
        }

        // 2. Read the prefetched Street View photos (if any): façade colours /
        //    materials and street trees.
        val photos = readPhotos(context, route, geo, osm, File(out, "seg"), onProgress)

        // 3. Build with what we have.
        post(onProgress, Progress("Building the 3D world…", 0.75f))
        val field = HeightField(context, route, allowNetwork = true)
        val heights = HeightSource { x, y -> field.height(x, y) }
        val tmp = File(out, "build.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            withContext(Dispatchers.Default) {
                SceneBuilder(route, osm, heights, { a -> photos?.facts?.get(a.id) }, photos?.trees.orEmpty()).build({ name, bytes -> File(tmp, name).writeBytes(bytes) }) { step ->
                    post(onProgress, Progress("Building: $step…", 0.8f))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp.deleteRecursively()
            throw e
        } catch (e: Throwable) {
            // Includes OutOfMemoryError: report it instead of crashing the app.
            tmp.deleteRecursively()
            val why = if (e is OutOfMemoryError) "ran out of memory" else (e.message ?: e.javaClass.simpleName)
            return@withContext Result("World build failed: $why", status(context, route))
        }

        // 4. Swap in the new world.
        out.listFiles()?.forEach { f -> if (f.isFile) f.delete() }
        tmp.listFiles()?.forEach { f -> f.renameTo(File(out, f.name)) }
        tmp.deleteRecursively()
        // Photos counted as prefetched (only some are analysed: nearby frames overlap).
        val photosUsed = if (photos != null) availablePhotos(context, route) else 0
        File(out, "stamp.txt").writeText("${stamp(route)}#$built/$total#photos=$photosUsed")
        post(onProgress, Progress("Done", 1f))
        Result(null, Status(built, total, photosUsed))
    }

    /** Frames closer together than this add little: skip them (time on the phone). */
    private const val PHOTO_SPACING_METERS = 15.0

    /**
     * Segment the route's prefetched Street View frames on the phone and read them
     * against the map. Null when there are none (map data only).
     */
    private suspend fun readPhotos(
        context: Context, route: Route, geo: Geo, osm: OsmData, cacheDir: File, onProgress: (Progress) -> Unit,
    ): PhotoAnalysis.Result? {
        val manifest = StreetViewCache.loadFor(context, route) ?: return null
        val dir = StreetViewCache.routeDir(context, manifest.routeId)
        val picked = ArrayList<com.bike.trainer.route.StreetViewSample>()
        var last = -1e9
        for (s in manifest.samples.sortedBy { it.distance }) {
            if (s.file == null || s.distance - last < PHOTO_SPACING_METERS || !File(dir, s.file).exists()) continue
            picked.add(s); last = s.distance
        }
        if (picked.isEmpty()) return null
        val segmenter = try {
            StreetSegmenter(context)
        } catch (e: Throwable) {
            android.util.Log.w("WorldStore", "segmenter unavailable", e)
            return null
        }
        val frames = ArrayList<SegFrame>()
        segmenter.use { seg ->
            for ((i, s) in picked.withIndex()) {
                coroutineContext.ensureActive()
                if (i % 5 == 0) post(onProgress, Progress("Reading Street View photos ${i + 1}/${picked.size}…", 0.65f + 0.1f * i / picked.size))
                val (labels, colours) = runCatching { seg.segment(File(dir, s.file!!), cacheDir) }.getOrNull() ?: continue
                val p = route.pointAt(s.distance)
                val x = geo.x(s.lon ?: p.lon); val y = geo.y(s.lat ?: p.lat)
                frames.add(SegFrame(x, y, p.heading, labels, colours))
            }
        }
        if (frames.isEmpty()) return null
        post(onProgress, Progress("Matching photos to buildings…", 0.75f))
        val buildings = osm.areas.filter { it.tags.containsKey("building") || it.tags.containsKey("building:part") }
        return withContext(Dispatchers.Default) { PhotoAnalysis(buildings).analyse(frames.asSequence()) }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun post(cb: (Progress) -> Unit, p: Progress) {
        main.post { cb(p) }
    }

    /** "s,w,n,e" box around a route section + corridor. */
    private fun sectionBbox(route: Route, d0: Double, d1: Double): String {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        var d = d0
        while (true) {
            val p = route.pointAt(d.coerceAtMost(route.totalDistance))
            minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
            minLon = minOf(minLon, p.lon); maxLon = maxOf(maxLon, p.lon)
            if (d >= d1 || d >= route.totalDistance) break
            d += 25.0
        }
        val dLat = CORRIDOR_METERS / 111_320.0
        val dLon = dLat / kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.1)
        return String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    /** Overpass QL: the points we draw, in the bounding box of this route section + corridor. */
    private fun pointsQuery(route: Route, d0: Double, d1: Double): String {
        val bbox = sectionBbox(route, d0, d1)
        // "qt" (quadtile order) is cheaper for the server than sorting by id.
        return "[out:json][timeout:90][bbox:$bbox];($POINT_FILTERS);out geom qt;"
    }

    /** Overpass QL: landmark buildings along the whole route (one small query). */
    private fun landmarksQuery(route: Route): String {
        val sections = ((route.totalDistance / LANDMARK_SECTION_METERS).toInt() + 1).coerceAtLeast(1)
        val q = StringBuilder("[out:json][timeout:120];(")
        for (s in 0 until sections) {
            val bbox = sectionBbox(route, s * LANDMARK_SECTION_METERS, min((s + 1) * LANDMARK_SECTION_METERS, route.totalDistance))
            for (f in LANDMARK_FILTERS) q.append(f).append('(').append(bbox).append(");")
        }
        return q.append(");out geom qt;").toString()
    }

    private sealed interface Answer {
        class Ok(val text: String) : Answer
        object TooBig : Answer
        object Failed : Answer
    }

    /**
     * Seconds until the main server gives us a query slot (it rations slots per
     * user and publishes when the next one frees up), or 0 if one is free now.
     */
    private fun slotWait(): Int = runCatching {
        http.newCall(Request.Builder().url(MAIN_SERVER + "status").header("User-Agent", UA).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (Regex("(\\d+) slots? available now").containsMatchIn(text)) 0
            else Regex("in (\\d+) seconds").findAll(text).map { it.groupValues[1].toInt() }.minOrNull() ?: 0
        }
    }.getOrDefault(0)

    private const val UA = "VibeBike/1.0 (indoor cycling app)"

    /** A request that's aborted (connection closed) as soon as the coroutine is cancelled. */
    private suspend fun fetch(req: Request): Pair<Int, String?> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(-1 to null))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = runCatching { response.use { it.body?.string() } }.getOrNull()
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(response.code to body))
                }
            })
        }

    /**
     * One Overpass request to [url]. For the main server we first wait for our query
     * slot. A server-side timeout / out-of-memory means the area is too big for one
     * request; anything else unsuccessful counts as busy (the caller re-queues).
     */
    private suspend fun overpass(q: String, url: String, note: (String) -> Unit): Answer {
        coroutineContext.ensureActive()
        if (url.startsWith(MAIN_SERVER)) {
            val wait = slotWait().coerceAtMost(180)
            if (wait > 0) {
                note("waiting ${wait}s for a free slot")
                delay(wait * 1000L + 500)
            }
        }
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .post(FormBody.Builder().add("data", q).build())
            .build()
        val (code, text) = fetch(req)
        if (code == 200 && text != null && text.contains("\"elements\"")) {
            // A runtime error remark means the answer was cut short.
            return if (text.contains("\"remark\"") && text.contains("runtime error")) Answer.TooBig else Answer.Ok(text)
        }
        if (code == 504 || (text != null && text.contains("runtime error"))) return Answer.TooBig
        note("failed (${if (code < 0) "no connection" else "HTTP $code"})")
        return Answer.Failed
    }
}
