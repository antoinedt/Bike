package com.bike.trainer.physics

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * A small forward-dynamics model that turns the rider's measured power output
 * (reported by the trainer) plus the current road gradient into a virtual
 * speed and travelled distance.
 *
 * The model integrates Newton's second law one tick at a time, which keeps it
 * numerically stable across the range of powers and gradients we care about and
 * gives the natural "feel" of accelerating, coasting and grinding up climbs.
 */
object CyclingPhysics {

    private const val GRAVITY = 9.81 // m/s^2
    private const val AIR_DENSITY = 1.225 // kg/m^3 at sea level, 15C
    private const val DRIVETRAIN_EFFICIENCY = 0.97
    private const val DEFAULT_CDA = 0.32 // m^2, drag area for a rider on the hoods
    private const val DEFAULT_CRR = 0.005 // rolling resistance coefficient

    /** Minimum speed used when converting power to a propulsive force (avoids /0). */
    private const val MIN_SPEED = 0.3 // m/s

    /**
     * Advance the simulation by [dtSeconds].
     *
     * @param speed current virtual speed in m/s.
     * @param powerWatts power the rider is currently producing (from the trainer).
     * @param gradeFraction road gradient as a ratio (0.05 == 5% climb).
     * @param totalMassKg combined rider + bike mass.
     * @return the new speed in m/s.
     */
    fun stepSpeed(
        speed: Double,
        powerWatts: Double,
        gradeFraction: Double,
        totalMassKg: Double,
        cda: Double = DEFAULT_CDA,
        crr: Double = DEFAULT_CRR,
        dtSeconds: Double,
    ): Double {
        val slopeAngle = atan(gradeFraction)
        val gravityForce = totalMassKg * GRAVITY * sin(slopeAngle)
        val rollingForce = totalMassKg * GRAVITY * cos(slopeAngle) * crr
        val effectiveSpeed = speed.coerceAtLeast(MIN_SPEED)
        val dragForce = 0.5 * AIR_DENSITY * cda * effectiveSpeed * effectiveSpeed
        val propulsiveForce = (powerWatts * DRIVETRAIN_EFFICIENCY) / effectiveSpeed

        val netForce = propulsiveForce - gravityForce - rollingForce - dragForce
        val acceleration = netForce / totalMassKg

        val newSpeed = speed + acceleration * dtSeconds
        // The rider can't roll backwards down the hill in this model.
        return newSpeed.coerceAtLeast(0.0)
    }

    /**
     * Steady-state speed for a constant power on a constant grade. Handy for
     * previews/tests; the live ride uses [stepSpeed] for smooth dynamics.
     */
    fun equilibriumSpeed(
        powerWatts: Double,
        gradeFraction: Double,
        totalMassKg: Double,
        cda: Double = DEFAULT_CDA,
        crr: Double = DEFAULT_CRR,
    ): Double {
        if (powerWatts <= 0.0) return 0.0
        var speed = 5.0
        // Newton-free fixed point iteration: converges quickly for this domain.
        repeat(60) {
            speed = stepSpeed(
                speed = speed,
                powerWatts = powerWatts,
                gradeFraction = gradeFraction,
                totalMassKg = totalMassKg,
                cda = cda,
                crr = crr,
                dtSeconds = 0.5,
            )
        }
        return speed
    }

    /** Convert a slope ratio to its percent representation. */
    fun gradeToPercent(gradeFraction: Double): Double = gradeFraction * 100.0

    /**
     * The steady-state power required to hold [speed] on [gradeFraction]. Used to
     * synthesize a believable power number in demo mode (no trainer connected).
     */
    fun powerForSteadySpeed(
        speed: Double,
        gradeFraction: Double,
        totalMassKg: Double,
        cda: Double = DEFAULT_CDA,
        crr: Double = DEFAULT_CRR,
    ): Double {
        if (speed <= 0.0) return 0.0
        val slopeAngle = atan(gradeFraction)
        val gravityForce = totalMassKg * GRAVITY * sin(slopeAngle)
        val rollingForce = totalMassKg * GRAVITY * cos(slopeAngle) * crr
        val dragForce = 0.5 * AIR_DENSITY * cda * speed * speed
        val totalForce = gravityForce + rollingForce + dragForce
        return (totalForce * speed / DRIVETRAIN_EFFICIENCY).coerceAtLeast(0.0)
    }

    /** Distance (m) covered while moving at [speed] for [dtSeconds]. */
    fun distanceStep(speed: Double, dtSeconds: Double): Double = speed * dtSeconds

    /** Convert m/s to km/h for display. */
    fun msToKmh(ms: Double): Double = ms * 3.6

    @Suppress("unused")
    private fun hypotGuard(a: Double, b: Double): Double = sqrt(a * a + b * b)
}
