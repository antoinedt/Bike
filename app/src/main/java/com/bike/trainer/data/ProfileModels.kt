package com.bike.trainer.data

import kotlinx.serialization.Serializable

/**
 * Best-effort interval windows (seconds), grouped like Strava's power curve:
 * short Sprints, medium Attacks, and long Climbs.
 */
val SPRINT_INTERVALS = listOf(15, 30, 60)
val ATTACK_INTERVALS = listOf(120, 180, 300, 600)
val CLIMB_INTERVALS = listOf(900, 1200, 1800, 2700, 3600)

/** All tracked windows, used by the ride stats calculator. */
val STANDARD_INTERVALS = SPRINT_INTERVALS + ATTACK_INTERVALS + CLIMB_INTERVALS

fun intervalLabel(seconds: Int): String = when {
    seconds < 60 -> "${seconds} s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h"
}

/** How long per-ride efforts are retained for the rolling 90-day bests. */
const val EFFORT_RETENTION_MS: Long = 100L * 24 * 3600 * 1000

/** Milliseconds in the rolling "recent bests" window. */
const val RECENT_WINDOW_MS: Long = 90L * 24 * 3600 * 1000

@Serializable
data class RiderProfile(
    val id: String,
    val name: String,
    val weightKg: Double = 75.0,
    /** Functional Threshold Power (watts); workout targets scale from this. */
    val ftpWatts: Int = 200,
)

/** A single ride's peak-power efforts, dated so we can roll a 90-day window. */
@Serializable
data class PowerEffort(
    val dateMillis: Long = 0L,
    /** Best average power (W) for this ride, keyed by interval length in seconds. */
    val powerW: Map<Int, Int> = emptyMap(),
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
    /** All-time best average power (W) keyed by interval length in seconds. */
    val bestAvgPowerW: Map<Int, Int> = emptyMap(),
    /** Recent per-ride efforts (within the retention window) for 90-day bests. */
    val powerEfforts: List<PowerEffort> = emptyList(),
) {
    /** Best average power (W) per interval over efforts at/after [sinceMillis]. */
    fun bestPowerSince(sinceMillis: Long): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        powerEfforts.forEach { e ->
            if (e.dateMillis >= sinceMillis) {
                e.powerW.forEach { (k, v) -> out[k] = maxOf(out[k] ?: v, v) }
            }
        }
        return out
    }
}

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
fun RiderStats.merged(ride: RideStatsSummary, nowMillis: Long = System.currentTimeMillis()): RiderStats {
    fun <T : Comparable<T>> bestMap(a: Map<Int, T>, b: Map<Int, T>): Map<Int, T> {
        val out = a.toMutableMap()
        b.forEach { (k, v) -> out[k] = maxOf(out[k] ?: v, v) }
        return out
    }
    // Record this ride's efforts (for the rolling 90-day bests) and prune any that
    // have aged out of the retention window.
    val efforts = (powerEfforts + PowerEffort(nowMillis, ride.bestAvgPowerW))
        .filter { nowMillis - it.dateMillis <= EFFORT_RETENTION_MS }
    return copy(
        totalDistanceMeters = totalDistanceMeters + ride.distanceMeters,
        totalRides = totalRides + 1,
        totalTimeSeconds = totalTimeSeconds + ride.durationSeconds,
        totalAscentMeters = totalAscentMeters + ride.ascentMeters,
        topSpeedKmh = maxOf(topSpeedKmh, ride.topSpeedKmh),
        bestAvgSpeedKmh = bestMap(bestAvgSpeedKmh, ride.bestAvgSpeedKmh),
        bestAvgPowerW = bestMap(bestAvgPowerW, ride.bestAvgPowerW),
        powerEfforts = efforts,
    )
}
