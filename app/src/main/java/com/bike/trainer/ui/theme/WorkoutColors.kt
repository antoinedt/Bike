package com.bike.trainer.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Workout power-zone colours (validated with the user) ----
val WorkoutEasy = Color(0xFF5BA3F5)        // light blue  (≤75% FTP)
val WorkoutTempo = Color(0xFF38C172)       // green       (76–94%)
val WorkoutThreshold = Color(0xFFF2C20E)   // yellow      (95–105%)
val WorkoutHard = Color(0xFFA855F7)        // purple      (≥106%)

/** Colour a workout bar/step by its intensity (fraction of FTP). */
fun workoutZoneColor(ftpFraction: Double): Color = when {
    ftpFraction < 0.76 -> WorkoutEasy
    ftpFraction < 0.95 -> WorkoutTempo
    ftpFraction < 1.06 -> WorkoutThreshold
    else -> WorkoutHard
}

// ---- In-ride adherence colours (current power vs target) ----
val PowerOnTarget = Color(0xFF38C172)  // green  — within range
val PowerTooLow = Color(0xFFE3493B)    // red    — below range
val PowerTooHigh = Color(0xFFA855F7)   // purple — above range

/** Green inside the tolerance band around [target], red below, purple above. */
fun adherenceColor(power: Int, target: Int, tolerance: Double): Color = when {
    target <= 0 -> PowerOnTarget
    power < target * (1.0 - tolerance) -> PowerTooLow
    power > target * (1.0 + tolerance) -> PowerTooHigh
    else -> PowerOnTarget
}
