package com.bike.trainer.session

/** Snapshot of a ride, rendered by the HUD. */
data class RideState(
    val status: RideStatus = RideStatus.NotStarted,
    val elapsedSeconds: Long = 0L,
    val distanceMeters: Double = 0.0,
    /** Position along the route (0..totalDistance); wraps when the route loops. */
    val lapPositionMeters: Double = 0.0,
    val totalDistanceMeters: Double = 0.0,
    val speedKmh: Double = 0.0,
    val powerWatts: Int = 0,
    val cadenceRpm: Int = 0,
    val heartRate: Int = 0,
    val gradePercent: Double = 0.0,
    val trainerGradePercent: Double = 0.0,
    val elevationMeters: Double = 0.0,
    val totalAscentMeters: Double = 0.0,
    val gear: Int = 6,
    val gearCount: Int = 12,
    val gearRatio: Double = 1.0,
    val avgPowerWatts: Int = 0,
    val energyKilojoules: Double = 0.0,
) {
    val progressFraction: Float
        get() = if (totalDistanceMeters > 0) {
            (lapPositionMeters / totalDistanceMeters).toFloat().coerceIn(0f, 1f)
        } else 0f
}

enum class RideStatus { NotStarted, Running, Paused, Finished }
