package com.bike.trainer.session

import com.bike.trainer.ble.TrainerConnectionManager
import com.bike.trainer.physics.CyclingPhysics
import com.bike.trainer.physics.VirtualGears
import com.bike.trainer.route.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives a single ride. Each tick it reads the rider's measured power from the
 * trainer, advances the virtual-speed/distance physics for the current road
 * grade, and pushes the new resistance grade (terrain + selected gear) back to
 * the trainer. It also records a [TrackPoint] roughly once a second for export.
 */
class RideEngine(
    val route: Route,
    private val trainer: TrainerConnectionManager,
    private val riderMassKg: Double,
    private val bikeMassKg: Double = 8.0,
    val gears: VirtualGears = VirtualGears(),
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    val recorder = RideRecorder()

    private var loop: Job? = null
    private var speedMs = 0.0
    private var distance = 0.0
    private var elapsedMs = 0L
    private var energyJoules = 0.0
    private var lastRecordedSecond = -1L

    private fun initialState(): RideState = RideState(
        status = RideStatus.NotStarted,
        totalDistanceMeters = route.totalDistance,
        totalAscentMeters = route.totalAscent,
        elevationMeters = route.elevationAt(0.0),
        gear = gears.current,
        gearCount = gears.gearCount,
        gearRatio = gears.displayRatio(),
        gradePercent = CyclingPhysics.gradeToPercent(route.gradeAt(0.0)),
    )

    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        recorder.start(System.currentTimeMillis())
        _state.value = _state.value.copy(status = RideStatus.Running)
        pushGradeToTrainer()
        loop = scope.launch {
            val tickMs = TICK_MS
            val dt = tickMs / 1000.0
            while (isActive) {
                delay(tickMs)
                if (_state.value.status != RideStatus.Running) continue
                tick(dt)
            }
        }
    }

    private fun tick(dt: Double) {
        val data = trainer.trainerData.value
        val power = data.powerWatts.toDouble()
        val totalMass = riderMassKg + bikeMassKg

        val terrainGrade = route.gradeAt(distance)
        speedMs = CyclingPhysics.stepSpeed(
            speed = speedMs,
            powerWatts = power,
            gradeFraction = terrainGrade,
            totalMassKg = totalMass,
            dtSeconds = dt,
        )
        distance += CyclingPhysics.distanceStep(speedMs, dt)
        elapsedMs += (dt * 1000).toLong()
        energyJoules += power * dt

        if (distance >= route.totalDistance) {
            distance = route.totalDistance
            finish()
            return
        }

        // Resistance the trainer should apply = terrain plus the gear offset.
        pushGradeToTrainer()

        val elapsedSeconds = elapsedMs / 1000
        val avgPower = if (elapsedSeconds > 0) (energyJoules / elapsedSeconds).toInt() else 0
        val point = route.pointAt(distance)

        _state.value = _state.value.copy(
            status = RideStatus.Running,
            elapsedSeconds = elapsedSeconds,
            distanceMeters = distance,
            speedKmh = CyclingPhysics.msToKmh(speedMs),
            powerWatts = data.powerWatts,
            cadenceRpm = data.cadenceRpm,
            heartRate = data.heartRate,
            gradePercent = CyclingPhysics.gradeToPercent(terrainGrade),
            trainerGradePercent = CyclingPhysics.gradeToPercent(terrainGrade + gears.gradeOffset()),
            elevationMeters = point.elevation,
            gear = gears.current,
            gearRatio = gears.displayRatio(),
            avgPowerWatts = avgPower,
            energyKilojoules = energyJoules / 1000.0,
        )

        // Record one point per elapsed second.
        if (elapsedSeconds != lastRecordedSecond) {
            lastRecordedSecond = elapsedSeconds
            recorder.add(
                TrackPoint(
                    timeMillis = recorder.startTimeMillis + elapsedMs,
                    lat = point.lat,
                    lon = point.lon,
                    elevation = point.elevation,
                    distanceMeters = distance,
                    speedKmh = CyclingPhysics.msToKmh(speedMs),
                    powerWatts = data.powerWatts,
                    cadenceRpm = data.cadenceRpm,
                    heartRate = data.heartRate,
                )
            )
        }
    }

    private fun pushGradeToTrainer() {
        val terrainGrade = route.gradeAt(distance)
        val effective = CyclingPhysics.gradeToPercent(terrainGrade + gears.gradeOffset())
        trainer.setSimulationGrade(effective)
    }

    fun pause() {
        if (_state.value.status == RideStatus.Running) {
            _state.value = _state.value.copy(status = RideStatus.Paused)
        }
    }

    fun resume() {
        if (_state.value.status == RideStatus.Paused) {
            _state.value = _state.value.copy(status = RideStatus.Running)
        }
    }

    fun finish() {
        loop?.cancel()
        loop = null
        _state.value = _state.value.copy(
            status = RideStatus.Finished,
            distanceMeters = distance,
        )
    }

    fun shiftUp() {
        if (gears.shiftUp()) {
            pushGradeToTrainer()
            _state.value = _state.value.copy(gear = gears.current, gearRatio = gears.displayRatio())
        }
    }

    fun shiftDown() {
        if (gears.shiftDown()) {
            pushGradeToTrainer()
            _state.value = _state.value.copy(gear = gears.current, gearRatio = gears.displayRatio())
        }
    }

    companion object {
        private const val TICK_MS = 250L
    }
}
