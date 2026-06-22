package com.bike.trainer.physics

/**
 * Virtual gearing for a smart trainer running in simulation mode.
 *
 * The trainer is told a "resistance grade". We take the real road gradient and
 * add a per-gear offset: shifting up adds resistance (a harder gear), shifting
 * down removes it. Because the trainer measures the rider's actual power, the
 * gear choice flows through to virtual speed naturally — spin an easy gear and
 * you produce less power and travel slower; grind a hard one and you go faster
 * for more effort. This also means gears still do something on flat ground,
 * unlike a pure grade-multiplier approach.
 */
class VirtualGears(
    val gearCount: Int = 12,
    /** Extra resistance grade (as a fraction, e.g. 0.006 == 0.6%) added per gear. */
    private val stepPerGear: Double = 0.006,
    initialGear: Int = 6,
) {
    /** 1-based index of the currently selected gear. */
    var current: Int = initialGear.coerceIn(1, gearCount)
        private set

    /** The "neutral" gear at which no resistance offset is applied. */
    private val neutralGear: Int = (gearCount + 1) / 2

    fun shiftUp(): Boolean {
        if (current >= gearCount) return false
        current += 1
        return true
    }

    fun shiftDown(): Boolean {
        if (current <= 1) return false
        current -= 1
        return true
    }

    fun selectGear(gear: Int) {
        current = gear.coerceIn(1, gearCount)
    }

    /** Resistance grade offset for the current gear, as a fraction. */
    fun gradeOffset(): Double = (current - neutralGear) * stepPerGear

    /**
     * A display ratio so the HUD can show something cassette-like. Lowest gear
     * is the easiest (smallest ratio), highest is the hardest.
     */
    fun displayRatio(): Double = ratioFor(current)

    private fun ratioFor(gear: Int): Double {
        val min = 0.75
        val max = 3.5
        if (gearCount <= 1) return max
        val t = (gear - 1).toDouble() / (gearCount - 1).toDouble()
        return min + t * (max - min)
    }

    /**
     * Demo-mode speed bump for the selected gear (km/h), relative to the neutral
     * gear. With no trainer to measure power, each gear up adds [perGear] km/h and
     * each gear down removes it, on top of the base demo cruise — so shifting
     * makes an obvious difference to how fast you go.
     */
    fun demoSpeedBonusKmh(perGear: Double = 10.0): Double = (current - neutralGear) * perGear
}
