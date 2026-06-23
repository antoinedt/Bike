package com.bike.trainer.session

import kotlin.math.roundToInt

/** One block of a structured workout: hold [ftpFraction] × FTP for [seconds]. */
data class WorkoutStep(val seconds: Int, val ftpFraction: Double)

/**
 * A structured workout. Power targets are stored as a fraction of FTP so the same
 * workout scales to any rider — actual watts are [ftpFraction] × the rider's FTP.
 */
data class Workout(
    val id: String,
    val name: String,
    /** Subjective difficulty, 1 (easy) .. 5 (very hard). */
    val difficulty: Int,
    val steps: List<WorkoutStep>,
) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }
    fun targetWatts(stepIndex: Int, ftp: Int): Int =
        (steps[stepIndex].ftpFraction * ftp).roundToInt()
}

/** The ten built-in workouts, plus lookup helpers. */
object WorkoutLibrary {

    private fun warmup(): List<WorkoutStep> =
        listOf(WorkoutStep(180, 0.45), WorkoutStep(180, 0.55), WorkoutStep(120, 0.65))

    private fun cooldown(): List<WorkoutStep> =
        listOf(WorkoutStep(240, 0.45))

    /** Repeat [block] [n] times. */
    private fun repeat(n: Int, block: List<WorkoutStep>): List<WorkoutStep> =
        (0 until n).flatMap { block }

    val all: List<Workout> = listOf(
        Workout(
            "recovery", "Recovery Spin", 1,
            listOf(WorkoutStep(180, 0.45)) + repeat(1, listOf(WorkoutStep(1500, 0.50))) + WorkoutStep(180, 0.45),
        ),
        Workout(
            "endurance", "Endurance Z2", 1,
            warmup() + WorkoutStep(2400, 0.65) + cooldown(),
        ),
        Workout(
            "tempo", "Tempo 3×10", 2,
            warmup() + repeat(3, listOf(WorkoutStep(600, 0.80), WorkoutStep(300, 0.55))) + cooldown(),
        ),
        Workout(
            "sweetspot", "Sweet Spot 3×12", 3,
            warmup() + repeat(3, listOf(WorkoutStep(720, 0.90), WorkoutStep(300, 0.55))) + cooldown(),
        ),
        Workout(
            "threshold", "Threshold 2×20", 4,
            warmup() + listOf(
                WorkoutStep(1200, 1.00), WorkoutStep(480, 0.55), WorkoutStep(1200, 1.00),
            ) + cooldown(),
        ),
        Workout(
            "overunders", "Over-Unders 3×9", 4,
            warmup() + repeat(
                3,
                repeat(3, listOf(WorkoutStep(120, 0.95), WorkoutStep(60, 1.05))) + WorkoutStep(300, 0.55),
            ) + cooldown(),
        ),
        Workout(
            "vo2max", "VO2max 5×3", 5,
            warmup() + repeat(5, listOf(WorkoutStep(180, 1.18), WorkoutStep(180, 0.50))) + cooldown(),
        ),
        Workout(
            "anaerobic", "Anaerobic 6×1", 5,
            warmup() + repeat(6, listOf(WorkoutStep(60, 1.30), WorkoutStep(120, 0.50))) + cooldown(),
        ),
        Workout(
            "sprints", "Sprints 10×15s", 3,
            warmup() + repeat(10, listOf(WorkoutStep(15, 1.60), WorkoutStep(165, 0.50))) + cooldown(),
        ),
        Workout(
            "pyramid", "Pyramid", 4,
            warmup() + listOf(
                WorkoutStep(60, 0.85), WorkoutStep(120, 0.90), WorkoutStep(180, 0.95),
                WorkoutStep(240, 1.00),
                WorkoutStep(180, 0.95), WorkoutStep(120, 0.90), WorkoutStep(60, 0.85),
            ) + WorkoutStep(300, 0.55) + cooldown(),
        ),
    )

    fun byId(id: String?): Workout? = id?.let { wid -> all.firstOrNull { it.id == wid } }
}
