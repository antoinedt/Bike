package com.bike.trainer.route

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Imports a real-world route from a GPX file (Strava / komoot / RideWithGPS
 * exports, or any recorded ride) and turns it into a [Route] the ride engine can
 * use, exactly like the procedurally generated corridors.
 *
 * Track points (`<trkpt>`) are preferred; if the file only has a planned route
 * (`<rtept>`) those are used instead. Elevation comes from the file's `<ele>`
 * tags when present; the profile is lightly smoothed and grade-limited so GPS
 * noise can't translate into absurd trainer resistance.
 */
object GpxImporter {

    private const val EARTH_RADIUS = 6_371_000.0 // metres
    private const val STEP_METERS = 10.0
    private const val MAX_GRADE = 0.20 // clamp to +/-20% to tame GPS spikes
    private const val SMOOTH_WINDOW = 5 // elevation moving-average window (odd)

    private data class RawPoint(val lat: Double, val lon: Double, val ele: Double?)

    class GpxFormatException(message: String) : Exception(message)

    /** Parse + build in one call. [name] is used as the route's display name. */
    fun import(name: String, input: InputStream, id: String? = null): Route {
        val raw = parse(input)
        if (raw.size < 2) {
            throw GpxFormatException("GPX file has fewer than 2 track points")
        }
        return buildRoute(name, raw, id)
    }

    // --------------------------------------------------------------- parsing

    private fun parse(input: InputStream): List<RawPoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val trackPoints = ArrayList<RawPoint>()
        val routePoints = ArrayList<RawPoint>()

        var pendingLat: Double? = null
        var pendingLon: Double? = null
        var pendingEle: Double? = null
        var pendingIsTrack = false
        var inPoint = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "trkpt", "rtept" -> {
                            inPoint = true
                            pendingIsTrack = parser.name == "trkpt"
                            pendingLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            pendingLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            pendingEle = null
                        }
                        "ele" -> if (inPoint) {
                            pendingEle = parser.nextText().trim().toDoubleOrNull()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "trkpt" || parser.name == "rtept") {
                        val lat = pendingLat
                        val lon = pendingLon
                        if (lat != null && lon != null) {
                            val point = RawPoint(lat, lon, pendingEle)
                            if (pendingIsTrack) trackPoints.add(point) else routePoints.add(point)
                        }
                        inPoint = false
                    }
                }
            }
            event = parser.next()
        }

        return if (trackPoints.isNotEmpty()) trackPoints else routePoints
    }

    // --------------------------------------------------------------- building

    private fun buildRoute(name: String, raw: List<RawPoint>, id: String? = null): Route {
        // 1. Drop consecutive duplicate fixes and compute cumulative distance.
        val cleaned = ArrayList<RawPoint>(raw.size)
        val cumulative = ArrayList<Double>(raw.size)
        var total = 0.0
        for (p in raw) {
            if (cleaned.isEmpty()) {
                cleaned.add(p)
                cumulative.add(0.0)
                continue
            }
            val prev = cleaned.last()
            val d = haversine(prev.lat, prev.lon, p.lat, p.lon)
            if (d < 0.5) continue // skip jitter < 0.5 m
            total += d
            cleaned.add(p)
            cumulative.add(total)
        }
        if (cleaned.size < 2) throw GpxFormatException("GPX route has no usable distance")

        val lat0 = cleaned.first().lat
        val lon0 = cleaned.first().lon
        val lat0Rad = lat0 * PI / 180.0

        // 2. Resample at a fixed spacing, interpolating position + elevation.
        val sampleCount = max(1, (total / STEP_METERS).toInt())
        val sampledLat = DoubleArray(sampleCount + 1)
        val sampledLon = DoubleArray(sampleCount + 1)
        val sampledEle = DoubleArray(sampleCount + 1)

        var seg = 0
        for (i in 0..sampleCount) {
            val target = min(i * STEP_METERS, total)
            while (seg < cleaned.size - 2 && cumulative[seg + 1] < target) seg++
            val segStart = cumulative[seg]
            val segEnd = cumulative[seg + 1]
            val span = segEnd - segStart
            val t = if (span > 0) (target - segStart) / span else 0.0
            val a = cleaned[seg]
            val b = cleaned[seg + 1]
            sampledLat[i] = a.lat + t * (b.lat - a.lat)
            sampledLon[i] = a.lon + t * (b.lon - a.lon)
            sampledEle[i] = interpolateEle(a.ele, b.ele, t)
        }

        // 3. Smooth and grade-limit the elevation profile.
        val smoothed = smooth(sampledEle)
        for (i in 1 until smoothed.size) {
            val maxDelta = MAX_GRADE * STEP_METERS
            val delta = (smoothed[i] - smoothed[i - 1]).coerceIn(-maxDelta, maxDelta)
            smoothed[i] = smoothed[i - 1] + delta
        }

        // 4. Project to local metres and derive headings.
        val xs = DoubleArray(smoothed.size)
        val ys = DoubleArray(smoothed.size)
        for (i in smoothed.indices) {
            xs[i] = EARTH_RADIUS * (sampledLon[i] - lon0) * PI / 180.0 * cos(lat0Rad)
            ys[i] = EARTH_RADIUS * (sampledLat[i] - lat0) * PI / 180.0
        }

        val points = ArrayList<RoutePoint>(smoothed.size)
        for (i in smoothed.indices) {
            val heading = when {
                i < smoothed.size - 1 -> atan2(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
                points.isNotEmpty() -> points.last().heading
                else -> 0.0
            }
            points.add(
                RoutePoint(
                    distance = i * STEP_METERS,
                    elevation = smoothed[i],
                    heading = heading,
                    x = xs[i],
                    y = ys[i],
                    lat = sampledLat[i],
                    lon = sampledLon[i],
                )
            )
        }

        return Route(
            name = name,
            seed = 0L,
            points = points,
            stepMeters = STEP_METERS,
            id = id,
        )
    }

    private fun interpolateEle(a: Double?, b: Double?, t: Double): Double = when {
        a != null && b != null -> a + t * (b - a)
        a != null -> a
        b != null -> b
        else -> 0.0
    }

    private fun smooth(values: DoubleArray): DoubleArray {
        if (values.size <= SMOOTH_WINDOW) return values.copyOf()
        val half = SMOOTH_WINDOW / 2
        val out = DoubleArray(values.size)
        for (i in values.indices) {
            var sum = 0.0
            var n = 0
            for (j in (i - half)..(i + half)) {
                if (j in values.indices) {
                    sum += values[j]
                    n++
                }
            }
            out[i] = sum / n
        }
        return out
    }

    /** Great-circle distance in metres between two lat/lon points. */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
            kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return EARTH_RADIUS * c
    }
}
