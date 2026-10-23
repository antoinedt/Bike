package com.bike.trainer.route

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh

/**
 * Maps a route's local metres (x = east, y = north from its first point — the same
 * projection GpxImporter uses) to and from latitude / longitude.
 */
class LocalProjection(route: Route) {
    private val lat0 = route.points.firstOrNull()?.lat ?: 0.0
    private val lon0 = route.points.firstOrNull()?.lon ?: 0.0
    private val mPerDegLat = EARTH_RADIUS * PI / 180.0
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat0)).coerceAtLeast(0.01)

    fun lat(y: Double) = lat0 + y / mPerDegLat
    fun lon(x: Double) = lon0 + x / mPerDegLon
    fun x(lon: Double) = (lon - lon0) * mPerDegLon
    fun y(lat: Double) = (lat - lat0) * mPerDegLat

    private companion object {
        const val EARTH_RADIUS = 6_371_000.0
    }
}

/**
 * OpenStreetMap vector tiles (OpenMapTiles schema, z14) from the free, key-less
 * OpenFreeMap service: buildings, land use, water and streets for the stylized
 * scenery. Cached on disk next to the terrain tiles, shared between routes.
 */
object OsmTiles {
    const val ZOOM = 14
    private const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"
    private const val FALLBACK_TEMPLATE = "https://tiles.openfreemap.org/planet/latest/{z}/{x}/{y}.pbf"

    /** Real map data reaches this far either side of the road. */
    private const val CORRIDOR_METERS = 1000.0
    private const val EARTH_RADIUS = 6_371_000.0

    /** Rough average tile size (city tiles are bigger, countryside much smaller). */
    const val APPROX_TILE_BYTES = 200_000L

    const val ATTRIBUTION = "© OpenMapTiles © OpenStreetMap contributors"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var template: String? = null

    fun root(context: Context): File = File(TerrainTiles.root(context).parentFile, "osm")

    fun tileFile(context: Context, x: Int, y: Int): File = File(root(context), "$ZOOM/${x}_$y.pbf")

    private val n = (1 shl ZOOM).toDouble()

    fun tileX(lon: Double): Double = (lon + 180.0) / 360.0 * n

    fun tileY(lat: Double): Double = TerrainTiles.tileY(lat) / (1 shl TerrainTiles.ZOOM) * n

    fun lonOf(tx: Double): Double = tx / n * 360.0 - 180.0

    fun latOf(ty: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * ty / n))))

    fun corridorTiles(route: Route): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        if (route.points.isEmpty()) return out
        val dLat = CORRIDOR_METERS / EARTH_RADIUS * 180.0 / PI
        var d = 0.0
        while (true) {
            val p = route.pointAt(d)
            val dLon = dLat / cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)
            for (x in floor(tileX(p.lon - dLon)).toInt()..floor(tileX(p.lon + dLon)).toInt()) {
                for (y in floor(tileY(p.lat + dLat)).toInt()..floor(tileY(p.lat - dLat)).toInt()) {
                    out.add(x to y)
                }
            }
            if (d >= route.totalDistance) break
            d = (d + 250.0).coerceAtMost(route.totalDistance)
        }
        return out
    }

    fun missingTiles(context: Context, route: Route): List<Pair<Int, Int>> =
        corridorTiles(route).filterNot { (x, y) -> tileFile(context, x, y).exists() }

    /** The current tile URL template (from the TileJSON, cached for the session). */
    private fun template(context: Context): String {
        template?.let { return it }
        val saved = File(root(context), "template.txt")
        val t = runCatching {
            http.newCall(Request.Builder().url(TILEJSON_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) null
                else JSONObject(resp.body!!.string()).getJSONArray("tiles").getString(0)
            }
        }.getOrNull()
        val chosen = t
            ?: saved.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
            ?: FALLBACK_TEMPLATE
        if (t != null) runCatching { saved.parentFile?.mkdirs(); saved.writeText(t) }
        template = chosen
        return chosen
    }

    /** Download one tile to disk (blocking). Returns false on any failure. */
    fun fetch(context: Context, x: Int, y: Int): Boolean {
        val dest = tileFile(context, x, y)
        if (dest.exists()) return true
        return runCatching {
            val url = template(context)
                .replace("{z}", ZOOM.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                // 204/404 = an empty (e.g. open-sea) tile: store it so we don't retry.
                val bytes = when {
                    resp.isSuccessful -> resp.body?.bytes() ?: ByteArray(0)
                    resp.code == 404 -> ByteArray(0)
                    else -> return false
                }
                dest.parentFile?.mkdirs()
                val tmp = File(dest.path + ".part")
                tmp.writeBytes(bytes)
                tmp.renameTo(dest)
            }
        }.getOrDefault(false)
    }

    /** Decoded features of a cached tile (gzip handled), or null if not cached. */
    fun load(context: Context, x: Int, y: Int, layers: Set<String>): List<Mvt.Feature>? {
        val f = tileFile(context, x, y)
        if (!f.exists()) return null
        var bytes = f.readBytes()
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            bytes = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        }
        return runCatching { Mvt.decode(bytes, layers) }.getOrNull()
    }
}
