package com.bike.trainer.session

/** A single recorded sample along the ride, used to build the export file. */
data class TrackPoint(
    val timeMillis: Long,
    val lat: Double,
    val lon: Double,
    val elevation: Double,
    val distanceMeters: Double,
    val speedKmh: Double,
    val powerWatts: Int,
    val cadenceRpm: Int,
    val heartRate: Int,
)

/**
 * Accumulates [TrackPoint]s during a ride so the finished activity can be
 * written to TCX and uploaded to Strava. Sampling is driven by the engine
 * (roughly once per second).
 */
class RideRecorder {
    private val points = ArrayList<TrackPoint>()
    var startTimeMillis: Long = 0L
        private set

    fun start(startMillis: Long) {
        points.clear()
        startTimeMillis = startMillis
    }

    fun add(point: TrackPoint) {
        points.add(point)
    }

    fun snapshot(): List<TrackPoint> = points.toList()

    val size: Int get() = points.size
}
