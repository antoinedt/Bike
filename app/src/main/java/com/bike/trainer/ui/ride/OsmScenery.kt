package com.bike.trainer.ui.ride

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.bike.trainer.route.LocalProjection
import com.bike.trainer.route.Mvt
import com.bike.trainer.route.OsmTiles
import com.bike.trainer.route.Route
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One OpenStreetMap feature converted to the route's local metres. */
class OsmFeature(
    val layer: String,
    val type: Int,
    val cls: String,
    val subclass: String,
    /** Rings / lines as interleaved local x,y metres. */
    val parts: List<DoubleArray>,
    val height: Double?,
    val minHeight: Double,
    val tunnel: Boolean,
) {
    val minX: Double; val minY: Double; val maxX: Double; val maxY: Double
    init {
        var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
        parts.forEach { p ->
            var i = 0
            while (i < p.size) {
                a = min(a, p[i]); c = max(c, p[i]); b = min(b, p[i + 1]); d = max(d, p[i + 1]); i += 2
            }
        }
        minX = a; minY = b; maxX = c; maxY = d
    }
}

/**
 * Real-world map data around a route (OpenMapTiles z14 from OpenFreeMap), decoded
 * lazily per tile and kept in a small cache. Only used on the world-builder thread.
 */
class OsmSource(
    private val context: Context,
    route: Route,
    private val allowNetwork: Boolean,
) {
    private val proj = LocalProjection(route)
    private val enabled = route.id != null && route.points.isNotEmpty()
    private val cache = object : LinkedHashMap<Long, List<OsmFeature>?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<OsmFeature>?>?) = size > 24
    }

    class Window(val features: List<OsmFeature>, val loadedAreas: List<DoubleArray>)

    /** Features of every tile touching the local bbox, plus the areas actually covered by data. */
    fun window(minX: Double, minY: Double, maxX: Double, maxY: Double): Window {
        if (!enabled) return Window(emptyList(), emptyList())
        val x0 = floor(OsmTiles.tileX(proj.lon(minX))).toInt()
        val x1 = floor(OsmTiles.tileX(proj.lon(maxX))).toInt()
        val y0 = floor(OsmTiles.tileY(proj.lat(maxY))).toInt()
        val y1 = floor(OsmTiles.tileY(proj.lat(minY))).toInt()
        val features = ArrayList<OsmFeature>()
        val areas = ArrayList<DoubleArray>()
        for (tx in x0..x1) for (ty in y0..y1) {
            val list = tile(tx, ty) ?: continue
            features.addAll(list)
            areas.add(
                doubleArrayOf(
                    proj.x(OsmTiles.lonOf(tx.toDouble())), proj.y(OsmTiles.latOf(ty + 1.0)),
                    proj.x(OsmTiles.lonOf(tx + 1.0)), proj.y(OsmTiles.latOf(ty.toDouble())),
                )
            )
        }
        return Window(features, areas)
    }

    private fun tile(tx: Int, ty: Int): List<OsmFeature>? {
        val k = HeightField.key(tx, ty)
        if (cache.containsKey(k)) return cache[k]
        var raw = OsmTiles.load(context, tx, ty, LAYERS)
        if (raw == null && allowNetwork && OsmTiles.fetch(context, tx, ty)) {
            raw = OsmTiles.load(context, tx, ty, LAYERS)
        }
        val converted = raw?.mapNotNull { convert(it, tx, ty) }
        cache[k] = converted
        return converted
    }

    private fun convert(f: Mvt.Feature, tx: Int, ty: Int): OsmFeature? {
        if (f.type != Mvt.LINE && f.type != Mvt.POLYGON) return null
        val ext = f.extent.toDouble()
        val parts = f.parts.map { p ->
            DoubleArray(p.size).also { out ->
                var i = 0
                while (i < p.size) {
                    out[i] = proj.x(OsmTiles.lonOf(tx + p[i] / ext))
                    out[i + 1] = proj.y(OsmTiles.latOf(ty + p[i + 1] / ext))
                    i += 2
                }
            }
        }
        return OsmFeature(
            layer = f.layer,
            type = f.type,
            cls = f.str("class") ?: "",
            subclass = f.str("subclass") ?: "",
            parts = parts,
            height = f.num("render_height") ?: f.num("height"),
            minHeight = f.num("render_min_height") ?: f.num("min_height") ?: 0.0,
            tunnel = f.str("brunnel") == "tunnel",
        )
    }

    companion object {
        val LAYERS = setOf("water", "waterway", "landcover", "landuse", "transportation", "building")
    }
}

/** Land classes baked into the class raster (red channel) to place trees sensibly. */
object LandClass {
    const val NONE = 0
    const val FOREST = 1
    const val GRASS = 2
    const val FARM = 3
    const val URBAN = 4
    const val WATER = 5
    const val ROAD = 6
    const val BUILDING = 7
    const val BARREN = 8
    const val LAND = 9
    const val WETLAND = 10
}

/**
 * Paints the ground texture (real land use, water and every street) and a coarse
 * class raster for one window. Unmapped areas stay transparent so the terrain's
 * own altitude colouring (rock, snow, meadow) shows through.
 */
class GroundPainter(
    private val originX: Double,
    private val originY: Double,
    private val size: Double,
) {
    val texture: Bitmap = Bitmap.createBitmap(TEX, TEX, Bitmap.Config.ARGB_8888)
    private val classBmp = Bitmap.createBitmap(CLS, CLS, Bitmap.Config.ARGB_8888)
    private val tex = Canvas(texture)
    private val cls = Canvas(classBmp)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val clsFill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
    private val clsStroke = Paint().apply {
        style = Paint.Style.STROKE; isAntiAlias = false; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var classes: IntArray? = null

    fun paint(win: OsmSource.Window) {
        // Unmapped-but-covered land: class only (texture stays see-through).
        win.loadedAreas.forEach { a ->
            clsFill.color = code(LandClass.LAND)
            val s = CLS / size
            cls.drawRect(
                ((a[0] - originX) * s).toFloat(), ((a[1] - originY) * s).toFloat(),
                ((a[2] - originX) * s).toFloat(), ((a[3] - originY) * s).toFloat(), clsFill,
            )
        }
        val f = win.features
        f.filter { it.layer == "landuse" }.forEach { area(it, landuseColor(it.cls), landuseClass(it.cls)) }
        f.filter { it.layer == "landcover" }.forEach { area(it, landcoverColor(it.cls), landcoverClass(it.cls)) }
        f.filter { it.layer == "water" }.forEach { area(it, WATER, LandClass.WATER) }
        f.filter { it.layer == "waterway" && !it.tunnel }.forEach { line(it, WATER, waterwayWidth(it.cls), LandClass.WATER) }
        f.filter { it.layer == "transportation" && !it.tunnel }
            .sortedBy { roadRank(it.cls) }
            .forEach { r -> roadStyle(r)?.let { (w, c) -> line(r, c, w, LandClass.ROAD) } }
        f.filter { it.layer == "building" }.forEach { area(it, BUILDING_FOOTPRINT, LandClass.BUILDING) }
    }

    /** Land class at a local position (after [paint]). */
    fun classAt(x: Double, y: Double): Int {
        val px = ((x - originX) / size * CLS).toInt()
        val py = ((y - originY) / size * CLS).toInt()
        if (px !in 0 until CLS || py !in 0 until CLS) return LandClass.NONE
        val c = classes ?: IntArray(CLS * CLS).also {
            classBmp.getPixels(it, 0, CLS, 0, 0, CLS, CLS)
            classes = it
        }
        return Color.red(c[py * CLS + px])
    }

    fun recycleClassRaster() = classBmp.recycle()

    private fun path(f: OsmFeature, scale: Double, close: Boolean): Path {
        val p = Path().apply { fillType = Path.FillType.EVEN_ODD }
        f.parts.forEach { part ->
            var i = 0
            while (i < part.size) {
                val x = ((part[i] - originX) * scale).toFloat()
                val y = ((part[i + 1] - originY) * scale).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                i += 2
            }
            if (close) p.close()
        }
        return p
    }

    private fun visible(f: OsmFeature) =
        f.maxX >= originX && f.minX <= originX + size && f.maxY >= originY && f.minY <= originY + size

    private fun area(f: OsmFeature, color: Int?, landClass: Int) {
        if (f.type != Mvt.POLYGON || !visible(f)) return
        if (color != null) {
            fill.color = color
            tex.drawPath(path(f, TEX / size, true), fill)
        }
        if (landClass != LandClass.NONE) {
            clsFill.color = code(landClass)
            cls.drawPath(path(f, CLS / size, true), clsFill)
        }
    }

    private fun line(f: OsmFeature, color: Int, widthMeters: Double, landClass: Int) {
        if (f.type != Mvt.LINE || !visible(f) || widthMeters <= 0) return
        stroke.color = color
        stroke.strokeWidth = max(1f, (widthMeters * TEX / size).toFloat())
        tex.drawPath(path(f, TEX / size, false), stroke)
        clsStroke.color = code(landClass)
        clsStroke.strokeWidth = max(1f, (widthMeters * CLS / size).toFloat())
        cls.drawPath(path(f, CLS / size, false), clsStroke)
    }

    private fun code(c: Int) = Color.rgb(c, 0, 0)

    companion object {
        const val TEX = 2048
        const val CLS = 1024

        private val WATER = Color.rgb(0x4D, 0x86, 0xC4)
        private val BUILDING_FOOTPRINT = Color.rgb(0xB5, 0xAB, 0xA1)

        private fun landuseColor(c: String): Int? = when (c) {
            "residential", "suburb", "quarter", "neighbourhood" -> Color.rgb(0xD6, 0xD1, 0xC8)
            "commercial", "retail" -> Color.rgb(0xDC, 0xCF, 0xC8)
            "industrial", "garages", "railway", "quarry", "military", "dam" -> Color.rgb(0xC8, 0xC3, 0xC6)
            "cemetery" -> Color.rgb(0xA8, 0xC4, 0x8E)
            "hospital", "school", "university", "college", "kindergarten", "library" -> Color.rgb(0xDC, 0xD3, 0xC2)
            "stadium", "pitch", "playground", "track", "theme_park", "zoo" -> Color.rgb(0x8F, 0xC0, 0x7A)
            else -> null
        }

        private fun landuseClass(c: String): Int = when (c) {
            "cemetery", "stadium", "pitch", "playground", "theme_park", "zoo" -> LandClass.GRASS
            "track" -> LandClass.BARREN
            else -> if (landuseColor(c) != null) LandClass.URBAN else LandClass.NONE
        }

        private fun landcoverColor(c: String): Int? = when (c) {
            "wood", "forest" -> Color.rgb(0x4F, 0x80, 0x3F)
            "grass" -> Color.rgb(0x82, 0xB6, 0x60)
            "farmland" -> Color.rgb(0xD6, 0xCC, 0x8C)
            "wetland" -> Color.rgb(0x8D, 0xB2, 0x8C)
            "sand" -> Color.rgb(0xE6, 0xD9, 0xA6)
            "rock" -> Color.rgb(0xA7, 0xA0, 0x97)
            "ice" -> Color.rgb(0xEE, 0xF2, 0xF6)
            else -> null
        }

        private fun landcoverClass(c: String): Int = when (c) {
            "wood", "forest" -> LandClass.FOREST
            "grass" -> LandClass.GRASS
            "farmland" -> LandClass.FARM
            "wetland" -> LandClass.WETLAND
            "sand", "rock", "ice" -> LandClass.BARREN
            else -> LandClass.NONE
        }

        private fun waterwayWidth(c: String) = when (c) {
            "river" -> 20.0
            "canal" -> 14.0
            "stream" -> 4.0
            else -> 2.0
        }

        private fun roadRank(c: String) = when (c) {
            "path", "track" -> 0
            "service" -> 1
            "minor" -> 2
            "rail", "transit" -> 3
            "tertiary" -> 4
            "secondary" -> 5
            "primary" -> 6
            "trunk" -> 7
            "motorway" -> 8
            else -> -1
        }

        /** (width m, colour) for a transportation line, or null to skip it. */
        private fun roadStyle(f: OsmFeature): Pair<Double, Int>? {
            val asphalt = Color.rgb(0x9A, 0x9D, 0xA2)
            return when (f.cls) {
                "motorway" -> 16.0 to Color.rgb(0x8E, 0x91, 0x96)
                "trunk" -> 14.0 to Color.rgb(0x8E, 0x91, 0x96)
                "primary" -> 12.0 to asphalt
                "secondary" -> 10.0 to asphalt
                "tertiary" -> 9.0 to asphalt
                "minor" -> 7.0 to Color.rgb(0xA3, 0xA6, 0xAA)
                "service" -> 4.5 to Color.rgb(0xA8, 0xAA, 0xAD)
                "track" -> 3.0 to Color.rgb(0xB4, 0x9A, 0x72)
                "path" -> 2.0 to if (f.subclass == "cycleway") Color.rgb(0xB8, 0x8E, 0x86) else Color.rgb(0xC8, 0xB5, 0x97)
                "rail", "transit" -> 3.0 to Color.rgb(0x6E, 0x6A, 0x66)
                else -> null
            }
        }
    }
}

/** Extrudes OSM building footprints into low-poly blocks with flat roofs. */
object BuildingMesher {
    private val WALLS = intArrayOf(
        Color.rgb(0xD8, 0xCB, 0xB5), Color.rgb(0xE6, 0xDC, 0xC8), Color.rgb(0xBD, 0xB8, 0xB2),
        Color.rgb(0xD9, 0xC4, 0x9B), Color.rgb(0xEC, 0xE8, 0xE0), Color.rgb(0xC9, 0xA9, 0x8E),
    )
    private val ROOF = floatArrayOf(0.60f, 0.57f, 0.54f)

    /**
     * Adds one building. [ground] samples the rendered terrain; [light] is the sun
     * direction in GL space. Returns false when the footprint is unusable.
     */
    fun add(
        out: FloatList, f: OsmFeature, ground: (Double, Double) -> Double?, light: FloatArray, seed: Int,
    ): Boolean {
        val rawHeight = f.height ?: 9.0
        if (rawHeight <= f.minHeight + 0.5) return false
        val wall = WALLS[(seed and 0x7FFFFFFF) % WALLS.size]
        val wr = Color.red(wall) / 255f; val wg = Color.green(wall) / 255f; val wb = Color.blue(wall) / 255f
        var any = false
        for (ring in f.parts) {
            var n = ring.size / 2
            if (n >= 2 && ring[0] == ring[2 * n - 2] && ring[1] == ring[2 * n - 1]) n--
            if (n < 3) continue
            val area = signedArea(ring, n)
            // MVT exteriors are clockwise in tile space; y flipped to north => area < 0.
            if (area >= 0 || abs(area) < 8.0) continue
            var base = Double.MAX_VALUE
            for (i in 0 until n) {
                val h = ground(ring[2 * i], ring[2 * i + 1]) ?: continue
                base = min(base, h)
            }
            if (base == Double.MAX_VALUE) continue
            val top = base + rawHeight
            val bottom = if (f.minHeight > 0) base + f.minHeight else base - 1.0
            // Walls.
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = ring[2 * i]; val ay = ring[2 * i + 1]
                val bx = ring[2 * j]; val by = ring[2 * j + 1]
                val dx = bx - ax; val dy = by - ay
                val len = kotlin.math.hypot(dx, dy)
                if (len < 0.05) continue
                // Clockwise ring: outward is to the left of travel.
                val ox = -dy / len; val oy = dx / len
                val dot = ox * light[0] + (-oy) * light[2]
                val shade = (0.62 + 0.42 * max(0.0, dot)).toFloat()
                val r = wr * shade; val g = wg * shade; val b = wb * shade
                out.vertex(ax, bottom, -ay, r, g, b)
                out.vertex(bx, bottom, -by, r, g, b)
                out.vertex(bx, top, -by, r, g, b)
                out.vertex(ax, bottom, -ay, r, g, b)
                out.vertex(bx, top, -by, r, g, b)
                out.vertex(ax, top, -ay, r, g, b)
            }
            // Flat roof.
            val roofShade = (0.55 + 0.5 * light[1]).toFloat().coerceAtMost(1f)
            val rr = ROOF[0] * roofShade; val rg = ROOF[1] * roofShade; val rb = ROOF[2] * roofShade
            triangulate(ring, n) { a, b, c ->
                out.vertex(ring[2 * a], top, -ring[2 * a + 1], rr, rg, rb)
                out.vertex(ring[2 * b], top, -ring[2 * b + 1], rr, rg, rb)
                out.vertex(ring[2 * c], top, -ring[2 * c + 1], rr, rg, rb)
            }
            any = true
        }
        return any
    }

    fun signedArea(ring: DoubleArray, n: Int): Double {
        var s = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += ring[2 * i] * ring[2 * j + 1] - ring[2 * j] * ring[2 * i + 1]
        }
        return s / 2
    }

    /** Point-in-polygon (even-odd) against the first [n] points of [ring]. */
    fun contains(ring: DoubleArray, n: Int, x: Double, y: Double): Boolean {
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[2 * i]; val yi = ring[2 * i + 1]
            val xj = ring[2 * j]; val yj = ring[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Ear clipping for a clockwise simple polygon; falls back to a fan. */
    private inline fun triangulate(ring: DoubleArray, n: Int, emit: (Int, Int, Int) -> Unit) {
        if (n > 160) {
            for (i in 1 until n - 1) emit(0, i, i + 1)
            return
        }
        val idx = ArrayList<Int>(n).apply { for (i in 0 until n) add(i) }
        var guard = n * n
        while (idx.size > 3 && guard-- > 0) {
            var clipped = false
            for (k in idx.indices) {
                val a = idx[(k + idx.size - 1) % idx.size]
                val b = idx[k]
                val c = idx[(k + 1) % idx.size]
                // Convex corner for a clockwise ring => negative cross product.
                val cross = (ring[2 * b] - ring[2 * a]) * (ring[2 * c + 1] - ring[2 * a + 1]) -
                    (ring[2 * b + 1] - ring[2 * a + 1]) * (ring[2 * c] - ring[2 * a])
                if (cross >= 0) continue
                var blocked = false
                for (m in idx) {
                    if (m == a || m == b || m == c) continue
                    if (inTriangle(ring, m, a, b, c)) { blocked = true; break }
                }
                if (blocked) continue
                emit(a, b, c)
                idx.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) break
        }
        if (idx.size == 3) emit(idx[0], idx[1], idx[2])
        else if (idx.size > 3) for (i in 1 until idx.size - 1) emit(idx[0], idx[i], idx[i + 1])
    }

    private fun inTriangle(r: DoubleArray, p: Int, a: Int, b: Int, c: Int): Boolean {
        val px = r[2 * p]; val py = r[2 * p + 1]
        fun side(i: Int, j: Int) =
            (r[2 * j] - r[2 * i]) * (py - r[2 * i + 1]) - (r[2 * j + 1] - r[2 * i + 1]) * (px - r[2 * i])
        val s1 = side(a, b); val s2 = side(b, c); val s3 = side(c, a)
        return (s1 <= 0 && s2 <= 0 && s3 <= 0) || (s1 >= 0 && s2 >= 0 && s3 >= 0)
    }
}
