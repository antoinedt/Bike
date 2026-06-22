package com.bike.trainer.data

import kotlinx.serialization.Serializable

/** Standard interval windows (seconds) for best-average tracking: 1/5/20/60 min. */
val STANDARD_INTERVALS = listOf(60, 300, 1200, 3600)

fun intervalLabel(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600} h"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "$seconds s"
}

@Serializable
data class RiderProfile(
    val id: String,
    val name: String,
    val weightKg: Double = 75.0,
)

/** Lifetime progression for a rider. */
@Serializable
data class RiderStats(
    val totalDistanceMeters: Double = 0.0,
    val totalRides: Int = 0,
    val totalTimeSeconds: Long = 0,
    val totalAscentMeters: Double = 0.0,
    val topSpeedKmh: Double = 0.0,
    /** Best average speed (km/h) keyed by interval length in seconds. */
    val bestAvgSpeedKmh: Map<Int, Double> = emptyMap(),
    /** Best average power (W) keyed by interval length in seconds. */
    val bestAvgPowerW: Map<Int, Int> = emptyMap(),
)

/** Per-rider Strava credentials + OAuth tokens (each rider connects their own). */
@Serializable
data class StravaAccount(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
) {
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val connected: Boolean get() = refreshToken.isNotBlank()
}

@Serializable
data class ProfileEntry(
    val profile: RiderProfile,
    val stats: RiderStats = RiderStats(),
    val strava: StravaAccount = StravaAccount(),
)

@Serializable
data class ProfilesState(
    val entries: List<ProfileEntry> = emptyList(),
    val activeId: String = "",
) {
    val active: ProfileEntry? get() = entries.firstOrNull { it.profile.id == activeId } ?: entries.firstOrNull()
}

/** The per-ride numbers folded into a rider's lifetime [RiderStats]. */
data class RideStatsSummary(
    val distanceMeters: Double,
    val durationSeconds: Long,
    val ascentMeters: Double,
    val topSpeedKmh: Double,
    val bestAvgSpeedKmh: Map<Int, Double>,
    val bestAvgPowerW: Map<Int, Int>,
)

/** Merge a finished ride into lifetime stats, keeping the best of each metric. */
fun RiderStats.merged(ride: RideStatsSummary): RiderStats {
    fun <T : Comparable<T>> bestMap(a: Map<Int, T>, b: Map<Int, T>): Map<Int, T> {
        val out = a.toMutableMap()
        b.forEach { (k, v) -> out[k] = maxOf(out[k] ?: v, v) }
        return out
    }
    return copy(
        totalDistanceMeters = totalDistanceMeters + ride.distanceMeters,
        totalRides = totalRides + 1,
        totalTimeSeconds = totalTimeSeconds + ride.durationSeconds,
        totalAscentMeters = totalAscentMeters + ride.ascentMeters,
        topSpeedKmh = maxOf(topSpeedKmh, ride.topSpeedKmh),
        bestAvgSpeedKmh = bestMap(bestAvgSpeedKmh, ride.bestAvgSpeedKmh),
        bestAvgPowerW = bestMap(bestAvgPowerW, ride.bestAvgPowerW),
    )
}
