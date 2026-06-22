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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin

/**
 * A stylized cyclist seen **from behind** (the rider you're chasing), so it
 * matches the forward-moving map camera. The legs pump alternately at a rate
 * driven by cadence (or estimated from speed), the body rocks with the pedal
 * stroke, and the rider lifts off the saddle and rocks harder as the gradient
 * steepens.
 *
 * Hand-rendered with shaded shapes/gradients — realistic-ish but stylized, and
 * fully offline (no model asset, no network).
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

    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last).coerceAtMost(50_000_000L)) / 1_000_000_000f
                    val ms = (speed / 3.6).toFloat()
                    val cad = when {
                        cadence > 0 -> cadence.toFloat()
                        ms > 0.5f -> 80f
                        else -> 0f
                    }
                    phase += cad / 60f * TWO_PI * dt
                }
                last = now
            }
        }
    }

    Canvas(modifier = modifier) {
        drawCyclistRear(phase, grade.toFloat())
    }
}

private const val TWO_PI = (2.0 * PI).toFloat()

// Palette from the reference: blue jersey, red shorts, dark helmet, black bike.
private val JERSEY_LIGHT = Color(0xFF3F73D6)
private val JERSEY_DARK = Color(0xFF274C97)
private val SHORTS = Color(0xFFCB3A2D)
private val SHORTS_HI = Color(0xFF9E2C22)
private val SKIN = Color(0xFFE6AC82)
private val SKIN_DARK = Color(0xFFC2895F)
private val HELMET = Color(0xFF1C2128)
private val HELMET_HI = Color(0xFF333B45)
private val SHOE = Color(0xFF0F1318)
private val WHEEL = Color(0xFF0D1116)
private val RIM = Color(0xFF3A4452)

private fun DrawScope.drawCyclistRear(phase: Float, gradePercent: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val ground = h * 0.98f

    val standF = ((gradePercent - 3f) / 6f).coerceIn(0f, 1f)
    // Out-of-the-saddle rock, plus a faint always-on rock.
    val rock = (0.04f + standF * 0.16f) * sin(phase)
    val swayTop = rock * w
    val swayHip = rock * w * 0.35f

    // ---- Rear wheel (a narrow ellipse, seen end-on) ----
    val wheelH = h * 0.46f
    val wheelW = w * 0.13f
    val wheelCx = cx
    val wheelCy = ground - wheelH / 2f
    drawOval(
        WHEEL,
        topLeft = Offset(wheelCx - wheelW / 2f, wheelCy - wheelH / 2f),
        size = Size(wheelW, wheelH),
    )
    drawOval(
        RIM,
        topLeft = Offset(wheelCx - wheelW / 2f, wheelCy - wheelH / 2f),
        size = Size(wheelW, wheelH),
        style = Stroke(width = wheelW * 0.18f),
    )
    drawLine(RIM, Offset(wheelCx, wheelCy - wheelH / 2f), Offset(wheelCx, wheelCy + wheelH / 2f), wheelW * 0.10f)

    // ---- Anchors ----
    val hipY = ground - wheelH * 0.62f - standF * h * 0.05f
    val hipHalf = w * 0.085f
    val torsoLen = h * (0.22f + standF * 0.03f)
    val shoulderY = hipY - torsoLen
    val shoulderHalf = w * 0.135f

    val barY = hipY - torsoLen * 0.62f
    val barHalf = w * 0.20f

    val pedalBaseY = ground - wheelH * 0.16f
    val pedalAmp = wheelH * 0.13f
    val pedalX = w * 0.13f
    val leftPedal = Offset(cx - pedalX + swayHip, pedalBaseY + pedalAmp * sin(phase))
    val rightPedal = Offset(cx + pedalX + swayHip, pedalBaseY + pedalAmp * sin(phase + PI.toFloat()))

    // ---- Handlebar (peeking out front) ----
    val barLeft = Offset(cx - barHalf + swayTop * 0.7f, barY)
    val barRight = Offset(cx + barHalf + swayTop * 0.7f, barY)
    drawLine(Color(0xFF161B22), barLeft, barRight, h * 0.018f, StrokeCap.Round)

    // ---- Legs (behind the body) ----
    drawLegRear(Offset(cx - hipHalf + swayHip, hipY), leftPedal, pedalBaseY, -1f, wheelH)
    drawLegRear(Offset(cx + hipHalf + swayHip, hipY), rightPedal, pedalBaseY, +1f, wheelH)

    // ---- Saddle / shorts seat ----
    val seat = Path().apply {
        moveTo(cx - hipHalf * 1.1f + swayHip, hipY)
        lineTo(cx + hipHalf * 1.1f + swayHip, hipY)
        lineTo(cx + hipHalf * 0.7f + swayHip, hipY + wheelH * 0.10f)
        lineTo(cx - hipHalf * 0.7f + swayHip, hipY + wheelH * 0.10f)
        close()
    }
    drawPath(seat, SHORTS)

    // ---- Torso (back / jersey) ----
    val lHipX = cx - hipHalf + swayHip
    val rHipX = cx + hipHalf + swayHip
    val lShX = cx - shoulderHalf + swayTop
    val rShX = cx + shoulderHalf + swayTop
    val torso = Path().apply {
        moveTo(lHipX, hipY)
        lineTo(lShX, shoulderY + torsoLen * 0.10f)
        quadraticBezierTo((lShX + rShX) / 2f, shoulderY - torsoLen * 0.06f, rShX, shoulderY + torsoLen * 0.10f)
        lineTo(rHipX, hipY)
        close()
    }
    drawPath(
        torso,
        brush = Brush.horizontalGradient(
            listOf(JERSEY_DARK, JERSEY_LIGHT, JERSEY_LIGHT, JERSEY_DARK),
            startX = lShX,
            endX = rShX,
        ),
    )
    // Spine shading + a number-band hint.
    drawLine(JERSEY_DARK, Offset((lHipX + rHipX) / 2f, hipY), Offset((lShX + rShX) / 2f, shoulderY), w * 0.012f)

    // ---- Arms (shoulders to bar) ----
    val lSh = Offset(lShX, shoulderY + torsoLen * 0.10f)
    val rSh = Offset(rShX, shoulderY + torsoLen * 0.10f)
    drawLine(SKIN_DARK, lSh, barLeft, w * 0.05f, StrokeCap.Round)
    drawLine(SKIN, rSh, barRight, w * 0.05f, StrokeCap.Round)

    // ---- Head + aero helmet ----
    val headCx = (lShX + rShX) / 2f
    val headCy = shoulderY - torsoLen * 0.04f
    drawLine(SKIN_DARK, Offset(headCx, shoulderY), Offset(headCx, headCy), w * 0.045f, StrokeCap.Round)
    val helmetW = w * 0.16f
    val helmetH = h * 0.12f
    drawOval(
        brush = Brush.verticalGradient(listOf(HELMET_HI, HELMET)),
        topLeft = Offset(headCx - helmetW / 2f, headCy - helmetH * 0.7f),
        size = Size(helmetW, helmetH),
    )
    // Helmet trim stripe.
    drawLine(
        JERSEY_LIGHT,
        Offset(headCx, headCy - helmetH * 0.7f),
        Offset(headCx, headCy + helmetH * 0.3f),
        w * 0.012f,
    )
}

private fun DrawScope.drawLegRear(hip: Offset, pedal: Offset, pedalBaseY: Float, side: Float, wheelH: Float) {
    // Knee bows outward; rises as the foot lifts.
    val footLift = (pedalBaseY - pedal.y) // >0 when foot is up
    val mid = Offset((hip.x + pedal.x) / 2f, (hip.y + pedal.y) / 2f)
    val knee = Offset(
        mid.x + side * wheelH * 0.10f,
        mid.y - wheelH * 0.05f - footLift * 0.45f,
    )
    // Thigh (shorts), calf (skin), shoe.
    drawLine(SHORTS, hip, knee, wheelH * 0.16f, StrokeCap.Round)
    drawLine(if (side < 0) SKIN_DARK else SKIN, knee, pedal, wheelH * 0.12f, StrokeCap.Round)
    drawOval(
        SHOE,
        topLeft = Offset(pedal.x - wheelH * 0.09f, pedal.y - wheelH * 0.035f),
        size = Size(wheelH * 0.18f, wheelH * 0.09f),
    )
}
