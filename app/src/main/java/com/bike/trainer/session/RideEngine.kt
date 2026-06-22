package com.bike.trainer.session

import com.bike.trainer.ble.HeartRateManager
import com.bike.trainer.ble.TrainerConnectionManager
import com.bike.trainer.ble.TrainerConnectionState
import kotlin.math.roundToInt
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
    private val heartRateManager: HeartRateManager? = null,
    /** Where on the route to begin (metres); the route loops, so this just offsets the start. */
    startDistanceMeters: Double = 0.0,
) {
    /** Absolute travelled distance (monotonic, includes the start offset). */
    private var distance = startDistanceMeters.coerceAtLeast(0.0)
    private val startDistance = distance

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    val recorder = RideRecorder()

    private var loop: Job? = null
    private var speedMs = 0.0
    private var elapsedMs = 0L
    private var energyJoules = 0.0
    private var lastRecordedSecond = -1L

    /** Position along the route, wrapped into [0, totalDistance). */
    private fun lapPos(): Double {
        val total = route.totalDistance
        return if (total > 0) distance.mod(total) else distance
    }

    /** Distance ridden this session (for the HUD / recording). */
    private fun ridden(): Double = distance - startDistance

    private fun initialState(): RideState = RideState(
        status = RideStatus.NotStarted,
        totalDistanceMeters = route.totalDistance,
        totalAscentMeters = route.totalAscent,
        lapPositionMeters = lapPos(),
        elevationMeters = route.elevationAt(lapPos()),
        gear = gears.current,
        gearCount = gears.gearCount,
        gearRatio = gears.displayRatio(),
        gradePercent = CyclingPhysics.gradeToPercent(route.gradeAt(lapPos())),
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
        val totalMass = riderMassKg + bikeMassKg
        val terrainGrade = route.gradeAt(lapPos())
        val connected = trainer.connectionState.value == TrainerConnectionState.Connected
        // Prefer a dedicated HR strap if connected; otherwise use the trainer's.
        val heartRate = heartRateManager?.heartRate?.value?.takeIf { it > 0 } ?: data.heartRate

        val power: Int
        val cadence: Int
        if (connected) {
            // Real ride: speed comes from the rider's measured power.
            power = data.powerWatts
            cadence = data.cadenceRpm
            speedMs = CyclingPhysics.stepSpeed(
                speed = speedMs,
                powerWatts = power.toDouble(),
                gradeFraction = terrainGrade,
                totalMassKg = totalMass,
                dtSeconds = dt,
            )
        } else {
            // Demo mode (no trainer): cruise ~20 km/h, then +10 km/h per gear up
            // from neutral (and -10 per gear down), easier on climbs and faster
            // downhill, with synthesized power/cadence so the HUD and rider move.
            val targetKmh = (DEMO_SPEED_KMH + gears.demoSpeedBonusKmh() -
                CyclingPhysics.gradeToPercent(terrainGrade) * 1.5)
                .coerceIn(5.0, 99.0)
            val targetMs = targetKmh / 3.6
            speedMs += (targetMs - speedMs) * (dt * 0.7).coerceAtMost(1.0)
            power = CyclingPhysics.powerForSteadySpeed(speedMs, terrainGrade, totalMass).roundToInt()
            cadence = if (speedMs > 0.5) 82 else 0
        }

        distance += CyclingPhysics.distanceStep(speedMs, dt)
        elapsedMs += (dt * 1000).toLong()
        energyJoules += power * dt
        // The route loops: on reaching the end we wrap back to the start and keep
        // going (the rider ends a ride with the Finish button, not by distance).

        // Resistance the trainer should apply = terrain plus the gear offset.
        pushGradeToTrainer()

        val elapsedSeconds = elapsedMs / 1000
        val avgPower = if (elapsedSeconds > 0) (energyJoules / elapsedSeconds).toInt() else 0
        val ridden = ridden()
        val point = route.pointAt(lapPos())

        _state.value = _state.value.copy(
            status = RideStatus.Running,
            elapsedSeconds = elapsedSeconds,
            distanceMeters = ridden,
            lapPositionMeters = lapPos(),
            speedKmh = CyclingPhysics.msToKmh(speedMs),
            powerWatts = power,
            cadenceRpm = cadence,
            heartRate = heartRate,
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
                    distanceMeters = ridden,
                    speedKmh = CyclingPhysics.msToKmh(speedMs),
                    powerWatts = power,
                    cadenceRpm = cadence,
                    heartRate = heartRate,
                )
            )
        }
    }

    private fun pushGradeToTrainer() {
        val terrainGrade = route.gradeAt(lapPos())
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
            distanceMeters = ridden(),
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
        /** Flat-ground cruising speed used in demo mode (no trainer connected). */
        private const val DEMO_SPEED_KMH = 20.0
    }
}
