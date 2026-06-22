package com.bike.trainer.route

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally generates a simple corridor with a believable elevation profile
 * and gentle left/right bends. Deterministic for a given seed so a route can be
 * regenerated/shared by seed alone.
 */
object RouteGenerator {

    /** Difficulty presets controlling length and how steep the hills get. */
    enum class Difficulty(
        val label: String,
        val minLengthMeters: Double,
        val maxLengthMeters: Double,
        val maxGrade: Double,
    ) {
        FLAT("Flat & Fast", 6_000.0, 12_000.0, 0.03),
        ROLLING("Rolling Hills", 8_000.0, 16_000.0, 0.06),
        MOUNTAIN("Mountain", 10_000.0, 22_000.0, 0.10),
    }

    private const val STEP_METERS = 10.0

    // Rough conversion near the synthesized origin (a quiet patch of countryside).
    private const val ORIGIN_LAT = 46.5197
    private const val ORIGIN_LON = 6.6323
    private const val METERS_PER_DEG_LAT = 111_320.0

    fun generate(
        difficulty: Difficulty = Difficulty.ROLLING,
        seed: Long = Random.nextLong(),
    ): Route {
        val random = Random(seed)
        val length = random.nextDouble(difficulty.minLengthMeters, difficulty.maxLengthMeters)
        val count = (length / STEP_METERS).toInt().coerceAtLeast(2)

        // --- Elevation: sum of a few sine octaves over distance, gently bounded.
        val baseElevation = random.nextDouble(80.0, 600.0)
        val octaves = buildList {
            // wavelength (m), amplitude (m), phase
            add(Triple(random.nextDouble(2500.0, 5000.0), random.nextDouble(30.0, 70.0), random.nextDouble(0.0, 2 * PI)))
            add(Triple(random.nextDouble(900.0, 1800.0), random.nextDouble(12.0, 30.0), random.nextDouble(0.0, 2 * PI)))
            add(Triple(random.nextDouble(300.0, 700.0), random.nextDouble(4.0, 12.0), random.nextDouble(0.0, 2 * PI)))
        }

        // --- Heading: a slowly varying curvature gives smooth, winding corners.
        var heading = random.nextDouble(0.0, 2 * PI)
        var curvature = 0.0 // radians per metre

        val points = ArrayList<RoutePoint>(count + 1)
        var x = 0.0
        var y = 0.0

        for (i in 0..count) {
            val distance = i * STEP_METERS

            // Elevation from octaves.
            var elevation = baseElevation
            for ((wavelength, amplitude, phase) in octaves) {
                elevation += amplitude * sin(2 * PI * distance / wavelength + phase)
            }

            // Clamp the local grade to the difficulty ceiling by easing elevation
            // toward the previous point when the step would be too steep.
            if (points.isNotEmpty()) {
                val prev = points.last()
                val maxDelta = difficulty.maxGrade * STEP_METERS
                val delta = (elevation - prev.elevation).coerceIn(-maxDelta, maxDelta)
                elevation = prev.elevation + delta
            }

            val (lat, lon) = toLatLon(x, y)
            points.add(
                RoutePoint(
                    distance = distance,
                    elevation = elevation,
                    heading = heading,
                    x = x,
                    y = y,
                    lat = lat,
                    lon = lon,
                )
            )

            // Advance ground position and wander the heading for the next step.
            x += sin(heading) * STEP_METERS
            y += cos(heading) * STEP_METERS
            // Curvature random-walks but is pulled back toward straight.
            curvature += random.nextDouble(-0.00012, 0.00012)
            curvature = (curvature * 0.96).coerceIn(-0.0035, 0.0035)
            heading += curvature * STEP_METERS
        }

        return Route(
            name = nameFor(difficulty, seed),
            seed = seed,
            points = points,
            stepMeters = STEP_METERS,
        )
    }

    private fun toLatLon(x: Double, y: Double): Pair<Double, Double> {
        val lat = ORIGIN_LAT + y / METERS_PER_DEG_LAT
        val metersPerDegLon = METERS_PER_DEG_LAT * cos(ORIGIN_LAT * PI / 180.0)
        val lon = ORIGIN_LON + x / metersPerDegLon
        return lat to lon
    }

    private fun nameFor(difficulty: Difficulty, seed: Long): String {
        val adjectives = listOf("Quiet", "Hidden", "Misty", "Golden", "Windswept", "Sunlit", "Lonely", "Emerald")
        val nouns = listOf("Valley", "Ridge", "Loop", "Pass", "Corridor", "Traverse", "Run", "Circuit")
        val r = Random(seed)
        return "${adjectives[r.nextInt(adjectives.size)]} ${nouns[r.nextInt(nouns.size)]} (${difficulty.label})"
    }
}
