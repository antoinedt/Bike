package com.bike.trainer.session

/** Per-step live state shown in the in-ride workout list. */
enum class StepStatus { PENDING, ACTIVE, DONE }

data class WorkoutStepLive(
    val seconds: Int,
    val targetWatts: Int,
    /** Power-target fraction of FTP for this step (drives the zone colour). */
    val ftpFraction: Double,
    val status: StepStatus,
    /** Seconds left in this step (only meaningful while ACTIVE). */
    val remainingSeconds: Int,
    /** Average power achieved (only meaningful once DONE). */
    val avgWatts: Int,
    /** How well this step matched its target, 0..100 (only meaningful once DONE). */
    val scorePct: Int,
)

/** Snapshot of the active structured workout, if any. */
data class WorkoutLive(
    val name: String,
    val activeIndex: Int,
    val targetWatts: Int,
    val steps: List<WorkoutStepLive>,
    /** Overall score so far, 0..100 (duration-weighted over finished steps). */
    val overallScore: Int,
    /** True once every step is done (the ride then continues as a free ride). */
    val completed: Boolean,
)

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
    val workout: WorkoutLive? = null,
) {
    val progressFraction: Float
        get() = if (totalDistanceMeters > 0) {
            (lapPositionMeters / totalDistanceMeters).toFloat().coerceIn(0f, 1f)
        } else 0f
}

enum class RideStatus { NotStarted, Running, Paused, Finished }
