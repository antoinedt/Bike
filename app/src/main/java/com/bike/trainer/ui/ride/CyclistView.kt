package com.bike.trainer.ui.ride

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.bike.trainer.ui.theme.BikeOrange
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A stylized side-on cyclist drawn with Canvas, overlaid on the 3D map. The legs
 * pedal at a rate driven by cadence (or estimated from speed), the wheels spin
 * with speed, and the rider lifts off the saddle and straightens up as the
 * gradient steepens — the classic "standing on the climb" look.
 *
 * This is a hand-rendered pseudo-3D rider, not a glTF model; it keeps the app
 * fully offline and dependency-free.
 */
@Composable
fun CyclistView(
    speedKmh: Double,
    cadenceRpm: Int,
    gradePercent: Double,
    modifier: Modifier = Modifier,
) {
    val speed by rememberUpdatedState(speedKmh)
    val cadence by rememberUpdatedState(cadenceRpm)
    val grade by rememberUpdatedState(gradePercent)

    var crank by remember { mutableFloatStateOf(0f) }
    var wheel by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last).coerceAtMost(50_000_000L)) / 1_000_000_000f
                    val ms = (speed / 3.6).toFloat()
                    val cad = when {
                        cadence > 0 -> cadence.toFloat()
                        ms > 0.5f -> 80f // moving without a cadence sensor
                        else -> 0f
                    }
                    crank += cad / 60f * TWO_PI * dt
                    wheel += ms / WHEEL_CIRCUMFERENCE * TWO_PI * dt
                }
                last = now
            }
        }
    }

    Canvas(modifier = modifier) {
        drawCyclist(crank, wheel, grade.toFloat())
    }
}

private const val TWO_PI = (2.0 * PI).toFloat()
private const val WHEEL_CIRCUMFERENCE = 2.1f // metres (700c road wheel)

private val BIKE_COLOR = Color(0xFF223040)
private val LEG_FAR = Color(0xFFB55A18)
private val SKIN = Color(0xFFE8B58A)

private fun DrawScope.drawCyclist(crank: Float, wheel: Float, gradePercent: Float) {
    val w = size.width
    val h = size.height
    val ground = h * 0.94f
    val wheelR = h * 0.17f

    val standF = ((gradePercent - 3f) / 6f).coerceIn(0f, 1f)
    // A little side-to-side sway when out of the saddle, synced to the pedal stroke.
    val sway = standF * sin(crank) * wheelR * 0.12f

    val rearHub = Offset(w * 0.32f, ground - wheelR)
    val frontHub = Offset(w * 0.74f, ground - wheelR)
    val bb = Offset(w * 0.52f, ground - wheelR * 0.95f)        // bottom bracket / crank centre
    val saddle = Offset(w * 0.44f, ground - wheelR * 2.15f)
    val bar = Offset(w * 0.73f, ground - wheelR * 1.95f)

    // ---- Bike frame + wheels ----
    drawWheel(rearHub, wheelR, wheel)
    drawWheel(frontHub, wheelR, wheel)
    val frame = Stroke(width = wheelR * 0.12f, cap = StrokeCap.Round)
    drawLine(BIKE_COLOR, rearHub, bb, frame.width, StrokeCap.Round)
    drawLine(BIKE_COLOR, bb, saddle, frame.width, StrokeCap.Round)
    drawLine(BIKE_COLOR, saddle, bar, frame.width, StrokeCap.Round)
    drawLine(BIKE_COLOR, bb, bar, frame.width, StrokeCap.Round)
    drawLine(BIKE_COLOR, bar, frontHub, frame.width, StrokeCap.Round)
    drawLine(BIKE_COLOR, rearHub, saddle, frame.width, StrokeCap.Round)

    // ---- Rider body anchors ----
    val hipSeated = Offset(saddle.x, saddle.y - wheelR * 0.15f)
    val hipStand = Offset(bb.x - wheelR * 0.15f + sway, bb.y - wheelR * 1.9f)
    val hip = lerp(hipSeated, hipStand, standF)

    val torsoLen = wheelR * 1.15f
    // Lean: ~32° from vertical seated, straightening toward ~16° standing.
    val leanDeg = 32f - 16f * standF
    val lean = leanDeg * (PI.toFloat() / 180f)
    val shoulder = Offset(hip.x + sin(lean) * torsoLen, hip.y - cos(lean) * torsoLen)

    // ---- Legs (two-bar IK from hip to each pedal) ----
    val thigh = wheelR * 1.0f
    val shin = wheelR * 1.05f
    val crankLen = wheelR * 0.5f
    val pedalNear = Offset(bb.x + cos(crank) * crankLen, bb.y - sin(crank) * crankLen)
    val pedalFar = Offset(bb.x + cos(crank + PI.toFloat()) * crankLen, bb.y - sin(crank + PI.toFloat()) * crankLen)

    // Far leg first (behind the frame), then near leg.
    drawLeg(hip, pedalFar, thigh, shin, LEG_FAR, wheelR)
    drawLine(BIKE_COLOR, bb, pedalFar, wheelR * 0.08f, StrokeCap.Round)
    drawLeg(hip, pedalNear, thigh, shin, BikeOrange, wheelR)
    drawLine(BIKE_COLOR, bb, pedalNear, wheelR * 0.08f, StrokeCap.Round)

    // ---- Torso, arm, head ----
    drawLine(BikeOrange, hip, shoulder, wheelR * 0.34f, StrokeCap.Round)
    drawLine(SKIN, shoulder, bar, wheelR * 0.12f, StrokeCap.Round)
    val head = Offset(shoulder.x + sin(lean) * wheelR * 0.35f, shoulder.y - cos(lean) * wheelR * 0.35f)
    drawCircle(SKIN, wheelR * 0.28f, head)
}

private fun DrawScope.drawWheel(hub: Offset, r: Float, angle: Float) {
    drawCircle(Color(0xFF11161C), r, hub, style = Stroke(width = r * 0.14f))
    // A couple of spokes so rotation is visible.
    for (k in 0 until 4) {
        val a = angle + k * (PI.toFloat() / 2f)
        drawLine(
            Color(0x66FFFFFF),
            hub,
            Offset(hub.x + cos(a) * r, hub.y + sin(a) * r),
            r * 0.05f,
        )
    }
}

private fun DrawScope.drawLeg(hip: Offset, foot: Offset, a: Float, b: Float, color: Color, wheelR: Float) {
    val knee = kneePosition(hip, foot, a, b)
    drawLine(color, hip, knee, wheelR * 0.22f, StrokeCap.Round)
    drawLine(color, knee, foot, wheelR * 0.18f, StrokeCap.Round)
}

/** Two-bar inverse kinematics; picks the forward-bending knee solution. */
private fun kneePosition(hip: Offset, foot: Offset, a: Float, b: Float): Offset {
    var dx = foot.x - hip.x
    var dy = foot.y - hip.y
    var d = hypot(dx, dy)
    val maxD = a + b - 0.001f
    if (d > maxD) {
        val s = maxD / d
        dx *= s; dy *= s; d = maxD
    }
    if (d < 0.001f) d = 0.001f
    val a2 = (a * a - b * b + d * d) / (2f * d)
    val hh = sqrt((a * a - a2 * a2).coerceAtLeast(0f))
    val mx = hip.x + a2 * dx / d
    val my = hip.y + a2 * dy / d
    val px = -dy / d
    val py = dx / d
    val k1 = Offset(mx + hh * px, my + hh * py)
    val k2 = Offset(mx - hh * px, my - hh * py)
    return if (k1.x > k2.x) k1 else k2 // knee forward (toward the handlebars)
}

private fun lerp(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
