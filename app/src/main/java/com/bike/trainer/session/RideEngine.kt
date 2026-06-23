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
    /** When true the route's gradient is ignored: flat physics, flat trainer resistance. */
    private val ignoreHills: Boolean = false,
    /** Optional structured workout; null = freeride. */
    private val workout: Workout? = null,
    /** Rider FTP (watts) the workout's power targets scale from. */
    private val ftp: Int = 200,
) {
    /** Absolute travelled distance (monotonic, includes the start offset). */
    private var distance = startDistanceMeters.coerceAtLeast(0.0)
    private val startDistance = distance

    // Per-step accumulated energy (J) and elapsed (s) for the workout averages.
    private val stepEnergy = DoubleArray(workout?.steps?.size ?: 0)
    private val stepTime = DoubleArray(workout?.steps?.size ?: 0)
    /** Cumulative step end-times (s) for fast active-step lookup. */
    private val stepEnds: IntArray = run {
        var acc = 0
        IntArray(workout?.steps?.size ?: 0) { i -> acc += workout!!.steps[i].seconds; acc }
    }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    val recorder = RideRecorder()

    private var loop: Job? = null
    private var speedMs = 0.0
    private var elapsedMs = 0L
    private var energyJoules = 0.0
    private var lastRecordedSecond = -1L
    private var trainerEverConnected = false

    /** True if a real trainer was connected at any point — gates saving to stats. */
    val recordedWithTrainer: Boolean get() = trainerEverConnected

    /** Position along the route, wrapped into [0, totalDistance). */
    private fun lapPos(): Double {
        val total = route.totalDistance
        return if (total > 0) distance.mod(total) else distance
    }

    /** Road gradient the simulation should feel — zeroed when hills are ignored. */
    private fun terrainGrade(): Double = if (ignoreHills) 0.0 else route.gradeAt(lapPos())

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
        gradePercent = CyclingPhysics.gradeToPercent(terrainGrade()),
        workout = buildWorkoutLive(0.0),
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

    /** Active workout step for [workoutSec] elapsed, or -1 (freeride / finished). */
    private fun activeStep(workoutSec: Double): Int {
        if (workout == null || stepEnds.isEmpty()) return -1
        val t = workoutSec.toInt()
        if (t >= stepEnds.last()) return -1
        for (i in stepEnds.indices) if (t < stepEnds[i]) return i
        return -1
    }

    /** Build the live workout list (per-step status / countdown / averages). */
    private fun buildWorkoutLive(workoutSec: Double): WorkoutLive? {
        val w = workout ?: return null
        val active = activeStep(workoutSec)
        val nowSec = workoutSec.toInt()
        val steps = w.steps.mapIndexed { i, s ->
            val target = w.targetWatts(i, ftp)
            val status = when {
                active < 0 -> StepStatus.DONE          // whole workout finished
                i < active -> StepStatus.DONE
                i == active -> StepStatus.ACTIVE
                else -> StepStatus.PENDING
            }
            val remaining = if (i == active) (stepEnds[i] - nowSec).coerceAtLeast(0) else s.seconds
            val avg = if (stepTime[i] > 0) (stepEnergy[i] / stepTime[i]).roundToInt() else 0
            WorkoutStepLive(s.seconds, target, status, remaining, avg)
        }
        val targetWatts = if (active >= 0) w.targetWatts(active, ftp) else 0
        return WorkoutLive(w.name, active, targetWatts, steps)
    }

    private fun tick(dt: Double) {
        val data = trainer.trainerData.value
        val totalMass = riderMassKg + bikeMassKg
        val terrainGrade = terrainGrade()
        val connected = trainer.connectionState.value == TrainerConnectionState.Connected
        if (connected) trainerEverConnected = true
        // Prefer a dedicated HR strap if connected; otherwise use the trainer's.
        val heartRate = heartRateManager?.heartRate?.value?.takeIf { it > 0 } ?: data.heartRate

        // Workout step this tick belongs to, and its target power.
        val stepIdx = activeStep(elapsedMs / 1000.0)
        val inWorkout = workout != null && stepIdx >= 0
        val targetWatts = if (inWorkout) workout!!.targetWatts(stepIdx, ftp) else 0

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
        } else if (inWorkout) {
            // Demo follows the workout's target power so the steps actually play.
            speedMs = CyclingPhysics.stepSpeed(
                speed = speedMs,
                powerWatts = targetWatts.toDouble(),
                gradeFraction = terrainGrade,
                totalMassKg = totalMass,
                dtSeconds = dt,
            )
            power = targetWatts
            cadence = if (speedMs > 0.5) 85 else 0
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

        // Per-step average power bookkeeping.
        if (inWorkout) {
            stepEnergy[stepIdx] += power * dt
            stepTime[stepIdx] += dt
        }

        distance += CyclingPhysics.distanceStep(speedMs, dt)
        elapsedMs += (dt * 1000).toLong()
        energyJoules += power * dt
        // The route loops: on reaching the end we wrap back to the start and keep
        // going (the rider ends a ride with the Finish button, not by distance).

        // During a workout the trainer holds the target power (ERG); otherwise it
        // simulates the road grade (terrain + gear offset).
        if (inWorkout) {
            trainer.setTargetPower(targetWatts)
        } else {
            pushGradeToTrainer()
        }

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
            workout = buildWorkoutLive(elapsedMs / 1000.0),
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
        val effective = CyclingPhysics.gradeToPercent(terrainGrade() + gears.gradeOffset())
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
