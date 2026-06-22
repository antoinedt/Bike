package com.bike.trainer.session

import com.bike.trainer.data.RideStatsSummary
import com.bike.trainer.data.STANDARD_INTERVALS

/**
 * Derives a [RideStatsSummary] from the recorded track points: total distance,
 * top speed, total climb, and the best rolling average speed / power for each
 * standard interval (1/5/20/60 min). Best efforts use a sliding time window.
 */
object RideStatsCalculator {

    fun compute(points: List<TrackPoint>): RideStatsSummary {
        if (points.size < 2) {
            val d = points.lastOrNull()?.distanceMeters ?: 0.0
            return RideStatsSummary(d, 0, 0.0, 0.0, emptyMap(), emptyMap())
        }

        val n = points.size
        val t0 = points.first().timeMillis
        val ts = DoubleArray(n) { (points[it].timeMillis - t0) / 1000.0 }
        val ds = DoubleArray(n) { points[it].distanceMeters }
        // Cumulative energy (J) so any window's average power = ΔE / Δt.
        val energy = DoubleArray(n)
        for (i in 1 until n) {
            energy[i] = energy[i - 1] + points[i - 1].powerWatts.toDouble() * (ts[i] - ts[i - 1])
        }

        val totalDuration = ts[n - 1]
        val distance = ds[n - 1] - ds[0]
        val topSpeed = points.maxOf { it.speedKmh }
        var ascent = 0.0
        for (i in 1 until n) {
            val dz = points[i].elevation - points[i - 1].elevation
            if (dz > 0) ascent += dz
        }

        val bestSpeed = HashMap<Int, Double>()
        val bestPower = HashMap<Int, Int>()
        for (w in STANDARD_INTERVALS) {
            if (totalDuration + 0.5 < w) continue
            var j = 0
            var spd = 0.0
            var pwr = 0.0
            for (i in 0 until n) {
                if (j < i) j = i
                while (j < n && ts[j] - ts[i] < w) j++
                if (j >= n) break
                val dt = ts[j] - ts[i]
                if (dt <= 0) continue
                spd = maxOf(spd, (ds[j] - ds[i]) / dt * 3.6)
                pwr = maxOf(pwr, (energy[j] - energy[i]) / dt)
            }
            if (spd > 0) bestSpeed[w] = spd
            if (pwr > 0) bestPower[w] = pwr.toInt()
        }

        return RideStatsSummary(
            distanceMeters = distance,
            durationSeconds = totalDuration.toLong(),
            ascentMeters = ascent,
            topSpeedKmh = topSpeed,
            bestAvgSpeedKmh = bestSpeed,
            bestAvgPowerW = bestPower,
        )
    }
}
