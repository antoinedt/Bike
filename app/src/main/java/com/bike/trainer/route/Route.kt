package com.bike.trainer.route

import kotlin.math.cos
import kotlin.math.sin

/**
 * One sampled point along the generated corridor.
 *
 * @param distance metres from the start.
 * @param elevation metres above the route's base altitude.
 * @param heading travel direction in radians (used to draw the bends).
 * @param x local east position in metres from the origin.
 * @param y local north position in metres from the origin.
 * @param lat synthesized latitude (for the Strava/GPS track).
 * @param lon synthesized longitude.
 */
data class RoutePoint(
    val distance: Double,
    val elevation: Double,
    val heading: Double,
    val x: Double,
    val y: Double,
    val lat: Double,
    val lon: Double,
)

/**
 * A self-contained, single-player "corridor": an elevation profile plus a gently
 * winding ground path. Everything is precomputed so look-ups during a ride are
 * cheap array interpolation.
 */
class Route(
    val name: String,
    val seed: Long,
    val points: List<RoutePoint>,
    val stepMeters: Double,
) {
    val totalDistance: Double = points.lastOrNull()?.distance ?: 0.0

    val minElevation: Double = points.minOfOrNull { it.elevation } ?: 0.0
    val maxElevation: Double = points.maxOfOrNull { it.elevation } ?: 0.0

    /** Total metres climbed across the whole route. */
    val totalAscent: Double = run {
        var sum = 0.0
        for (i in 1 until points.size) {
            val d = points[i].elevation - points[i - 1].elevation
            if (d > 0) sum += d
        }
        sum
    }

    private fun indexFor(distance: Double): Int {
        if (stepMeters <= 0.0) return 0
        return (distance / stepMeters).toInt().coerceIn(0, points.size - 1)
    }

    /** Grade (slope ratio) of the segment containing [distance]. */
    fun gradeAt(distance: Double): Double {
        if (points.size < 2) return 0.0
        val i = indexFor(distance).coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        val dd = b.distance - a.distance
        if (dd <= 0.0) return 0.0
        return (b.elevation - a.elevation) / dd
    }

    /** Linearly interpolated elevation at [distance]. */
    fun elevationAt(distance: Double): Double {
        if (points.isEmpty()) return 0.0
        if (distance <= 0.0) return points.first().elevation
        if (distance >= totalDistance) return points.last().elevation
        val i = indexFor(distance).coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        val span = b.distance - a.distance
        val t = if (span > 0) (distance - a.distance) / span else 0.0
        return a.elevation + t * (b.elevation - a.elevation)
    }

    /** Interpolated point (position + heading) at [distance], for rendering. */
    fun pointAt(distance: Double): RoutePoint {
        if (points.isEmpty()) {
            return RoutePoint(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val clamped = distance.coerceIn(0.0, totalDistance)
        val i = indexFor(clamped).coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        val span = b.distance - a.distance
        val t = if (span > 0) (clamped - a.distance) / span else 0.0
        return RoutePoint(
            distance = clamped,
            elevation = a.elevation + t * (b.elevation - a.elevation),
            heading = a.heading + t * (b.heading - a.heading),
            x = a.x + t * (b.x - a.x),
            y = a.y + t * (b.y - a.y),
            lat = a.lat + t * (b.lat - a.lat),
            lon = a.lon + t * (b.lon - a.lon),
        )
    }

    companion object {
        /** Unit direction vector for a heading; small helper for renderers. */
        fun headingVector(heading: Double): Pair<Double, Double> =
            sin(heading) to cos(heading)
    }
}
