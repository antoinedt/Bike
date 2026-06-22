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
import kotlin.math.cos
import kotlin.math.sin

/**
 * A stylized road cyclist seen **from behind** (the rider you're chasing), so it
 * matches the forward-moving map camera. It's hand-rendered on a [Canvas] —
 * shaded body shapes with bezier curves, an actual road bike (spoked wheels,
 * frame triangle, drop bars) — fully offline, no model asset or network.
 *
 * The legs pump alternately at a rate driven by cadence (or estimated from
 * speed), the body rocks with the pedal stroke, and the rider lifts off the
 * saddle and rocks harder as the gradient steepens.
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
private const val PI_F = PI.toFloat()

// Palette from the reference: blue jersey, red shorts, dark aero helmet, black bike.
private val JERSEY_HI = Color(0xFF6E9BEC)
private val JERSEY_LIGHT = Color(0xFF4C82E6)
private val JERSEY_MID = Color(0xFF3568CC)
private val JERSEY_DARK = Color(0xFF234A93)
private val SHORTS = Color(0xFFD23D2E)
private val SHORTS_HI = Color(0xFFE85746)
private val SHORTS_DARK = Color(0xFF9E2C22)
private val SKIN = Color(0xFFE9B48C)
private val SKIN_DARK = Color(0xFFC68A60)
private val HELMET = Color(0xFF20262E)
private val HELMET_HI = Color(0xFF3D4856)
private val HELMET_VENT = Color(0xFF0C1014)
private val SHOE = Color(0xFF12161B)
private val SHOE_HI = Color(0xFF2C343F)
private val TIRE = Color(0xFF131720)
private val RIM = Color(0xFF8A93A0)
private val RIM_DARK = Color(0xFF454D58)
private val HUB = Color(0xFFAEB7C3)
private val FRAME = Color(0xFF161B22)
private val FRAME_HI = Color(0xFF3A434F)
private val SHADOW = Color(0x33000000)

private fun DrawScope.drawCyclistRear(phase: Float, gradePercent: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val ground = h * 0.99f

    // Out-of-the-saddle standing factor and the body rock that goes with it.
    val standF = ((gradePercent - 3f) / 6f).coerceIn(0f, 1f)
    val rock = (0.03f + standF * 0.14f) * sin(phase)
    val sway = rock * w

    // ---- Bike geometry ----
    val rearR = h * 0.255f
    val rearC = Offset(cx + w * 0.005f, ground - rearR)
    val frontR = h * 0.205f
    val frontC = Offset(cx + w * 0.02f, ground - frontR - h * 0.07f)

    val bb = Offset(cx + sway * 0.4f, ground - rearR * 0.62f)   // bottom bracket / cranks
    val saddle = Offset(cx + sway, rearC.y - rearR * 0.74f)
    val headTube = Offset(frontC.x, frontC.y - frontR * 0.36f)
    val barC = Offset(cx + sway * 0.55f, saddle.y + (bb.y - saddle.y) * 0.30f)

    // Ground contact shadow.
    drawOval(
        SHADOW,
        topLeft = Offset(cx - w * 0.34f, ground - h * 0.03f),
        size = Size(w * 0.68f, h * 0.05f),
    )

    // Front wheel sits "ahead" — drawn first (furthest) and a touch dimmer.
    drawBikeWheel(frontC, frontR, dim = 0.82f)

    // ---- Frame (drawn behind the rear wheel hub area) ----
    val rearHub = rearC
    drawFrameTube(bb, saddle, w * 0.030f)                 // seat tube
    drawFrameTube(bb, rearHub, w * 0.024f)                // chainstay
    drawFrameTube(saddle, rearHub, w * 0.020f)            // seat stay
    drawFrameTube(bb, headTube, w * 0.030f)               // down tube
    drawFrameTube(saddle, headTube, w * 0.024f)           // top tube
    drawFrameTube(headTube, frontC, w * 0.022f)           // fork

    // Rear wheel over the rear triangle.
    drawBikeWheel(rearC, rearR, dim = 1f)

    // Cranks + pedals.
    val crankR = rearR * 0.40f
    val leftPedal = Offset(bb.x - w * 0.025f, bb.y + crankR * sin(phase))
    val rightPedal = Offset(bb.x + w * 0.025f, bb.y + crankR * sin(phase + PI_F))
    drawCircle(FRAME, crankR * 0.42f, bb)
    drawCircle(FRAME_HI, crankR * 0.20f, bb)
    drawLine(FRAME, bb, leftPedal, w * 0.016f, StrokeCap.Round)
    drawLine(FRAME, bb, rightPedal, w * 0.016f, StrokeCap.Round)

    // ---- Anchors for the rider ----
    val hipHalf = w * 0.085f
    val shoulderHalf = w * 0.140f
    val hipY = saddle.y
    val torsoLen = (saddle.y - bb.y) * (0.92f + standF * 0.12f)
    val shoulderY = hipY - torsoLen

    val lHip = Offset(cx - hipHalf + sway, hipY)
    val rHip = Offset(cx + hipHalf + sway, hipY)
    val lSh = Offset(cx - shoulderHalf + sway * 1.25f, shoulderY)
    val rSh = Offset(cx + shoulderHalf + sway * 1.25f, shoulderY)

    // ---- Legs (behind the torso / shorts) ----
    drawLeg(lHip, leftPedal, bb.y, side = -1f, scale = rearR)
    drawLeg(rHip, rightPedal, bb.y, side = +1f, scale = rearR)

    // Handlebar (drop bar peeking out front) + hoods.
    val barHalf = w * 0.16f
    val lBar = Offset(barC.x - barHalf, barC.y)
    val rBar = Offset(barC.x + barHalf, barC.y)
    val bar = Path().apply {
        moveTo(lBar.x, lBar.y)
        quadraticBezierTo(barC.x, barC.y - h * 0.03f, rBar.x, rBar.y)
    }
    drawPath(bar, FRAME, style = Stroke(width = h * 0.016f, cap = StrokeCap.Round))

    // ---- Shorts / saddle seat ----
    val seat = Path().apply {
        moveTo(lHip.x - hipHalf * 0.15f, hipY)
        cubicTo(
            lHip.x, hipY + rearR * 0.13f,
            rHip.x, hipY + rearR * 0.13f,
            rHip.x + hipHalf * 0.15f, hipY,
        )
        cubicTo(
            rHip.x, hipY - rearR * 0.05f,
            lHip.x, hipY - rearR * 0.05f,
            lHip.x - hipHalf * 0.15f, hipY,
        )
        close()
    }
    drawPath(
        seat,
        brush = Brush.verticalGradient(
            listOf(SHORTS_HI, SHORTS, SHORTS_DARK),
            startY = hipY - rearR * 0.05f,
            endY = hipY + rearR * 0.13f,
        ),
    )

    // ---- Torso (the rider's back, in the jersey) ----
    val back = Path().apply {
        moveTo(lHip.x, lHip.y)
        // left flank up to left shoulder
        cubicTo(
            lHip.x - hipHalf * 0.25f, (lHip.y + lSh.y) * 0.5f,
            lSh.x - shoulderHalf * 0.12f, lSh.y + torsoLen * 0.22f,
            lSh.x, lSh.y,
        )
        // shoulders / upper back curve
        quadraticBezierTo((lSh.x + rSh.x) * 0.5f, shoulderY - torsoLen * 0.10f, rSh.x, rSh.y)
        // right shoulder down to right hip
        cubicTo(
            rSh.x + shoulderHalf * 0.12f, rSh.y + torsoLen * 0.22f,
            rHip.x + hipHalf * 0.25f, (rHip.y + rSh.y) * 0.5f,
            rHip.x, rHip.y,
        )
        close()
    }
    drawPath(
        back,
        brush = Brush.verticalGradient(
            listOf(JERSEY_HI, JERSEY_LIGHT, JERSEY_MID, JERSEY_DARK),
            startY = shoulderY - torsoLen * 0.10f,
            endY = hipY,
        ),
    )
    // Spine shadow + shoulder-blade hints + a hem band.
    val midX = (lHip.x + rHip.x) * 0.5f
    drawLine(JERSEY_DARK, Offset(midX, hipY), Offset((lSh.x + rSh.x) * 0.5f, shoulderY + torsoLen * 0.12f), w * 0.012f, StrokeCap.Round)
    drawLine(JERSEY_HI.copy(alpha = 0.6f), Offset(lSh.x + shoulderHalf * 0.3f, shoulderY + torsoLen * 0.2f), Offset(lHip.x + hipHalf * 0.2f, hipY - torsoLen * 0.15f), w * 0.010f, StrokeCap.Round)
    drawLine(JERSEY_HI.copy(alpha = 0.6f), Offset(rSh.x - shoulderHalf * 0.3f, shoulderY + torsoLen * 0.2f), Offset(rHip.x - hipHalf * 0.2f, hipY - torsoLen * 0.15f), w * 0.010f, StrokeCap.Round)
    drawLine(JERSEY_DARK, lHip, rHip, w * 0.018f, StrokeCap.Round)

    // ---- Arms (shoulders to the bar, with a bent elbow) ----
    drawArm(lSh, lBar, side = -1f, scale = w)
    drawArm(rSh, rBar, side = +1f, scale = w)

    // ---- Neck + head + aero helmet ----
    val neck = Offset((lSh.x + rSh.x) * 0.5f, shoulderY + torsoLen * 0.02f)
    val headW = w * 0.135f
    val headCy = shoulderY - torsoLen * 0.24f
    drawLine(SKIN_DARK, neck, Offset(neck.x, headCy + headW * 0.35f), w * 0.05f, StrokeCap.Round)

    // Helmet: rounded dome + a swept tail (teardrop, rear view).
    val helmet = Path().apply {
        moveTo(neck.x - headW * 0.52f, headCy + headW * 0.25f)
        cubicTo(
            neck.x - headW * 0.60f, headCy - headW * 0.55f,
            neck.x + headW * 0.60f, headCy - headW * 0.55f,
            neck.x + headW * 0.52f, headCy + headW * 0.25f,
        )
        // tail sweeping down the back of the head
        cubicTo(
            neck.x + headW * 0.30f, headCy + headW * 0.62f,
            neck.x - headW * 0.30f, headCy + headW * 0.62f,
            neck.x - headW * 0.52f, headCy + headW * 0.25f,
        )
        close()
    }
    drawPath(
        helmet,
        brush = Brush.verticalGradient(
            listOf(HELMET_HI, HELMET, HELMET),
            startY = headCy - headW * 0.55f,
            endY = headCy + headW * 0.62f,
        ),
    )
    // Vents (a couple of dark slots) + a colour trim line down the centre.
    drawLine(HELMET_VENT, Offset(neck.x - headW * 0.18f, headCy - headW * 0.15f), Offset(neck.x - headW * 0.12f, headCy + headW * 0.28f), w * 0.012f, StrokeCap.Round)
    drawLine(HELMET_VENT, Offset(neck.x + headW * 0.18f, headCy - headW * 0.15f), Offset(neck.x + headW * 0.12f, headCy + headW * 0.28f), w * 0.012f, StrokeCap.Round)
    drawLine(JERSEY_LIGHT, Offset(neck.x, headCy - headW * 0.45f), Offset(neck.x, headCy + headW * 0.30f), w * 0.010f, StrokeCap.Round)
}

/** A wheel seen at a rear three-quarter angle: tire, rim, spokes, hub. */
private fun DrawScope.drawBikeWheel(c: Offset, r: Float, dim: Float) {
    val rx = r * 0.74f
    val ry = r
    val tire = TIRE.copy(alpha = dim)
    // Tire.
    drawOval(
        tire,
        topLeft = Offset(c.x - rx, c.y - ry),
        size = Size(rx * 2f, ry * 2f),
        style = Stroke(width = r * 0.15f),
    )
    // Rim (slightly inside the tire).
    val rimRx = rx * 0.86f
    val rimRy = ry * 0.86f
    drawOval(
        RIM_DARK.copy(alpha = dim),
        topLeft = Offset(c.x - rimRx, c.y - rimRy),
        size = Size(rimRx * 2f, rimRy * 2f),
        style = Stroke(width = r * 0.05f),
    )
    drawOval(
        RIM.copy(alpha = dim * 0.6f),
        topLeft = Offset(c.x - rimRx, c.y - rimRy),
        size = Size(rimRx * 2f, rimRy * 2f),
        style = Stroke(width = r * 0.018f),
    )
    // Spokes.
    val spokes = 14
    for (i in 0 until spokes) {
        val a = (i / spokes.toFloat()) * TWO_PI
        val e = Offset(c.x + rimRx * 0.95f * cos(a), c.y + rimRy * 0.95f * sin(a))
        drawLine(RIM.copy(alpha = dim * 0.4f), c, e, r * 0.012f)
    }
    // Hub.
    drawCircle(HUB.copy(alpha = dim), r * 0.075f, c)
    drawCircle(FRAME.copy(alpha = dim), r * 0.035f, c)
}

private fun DrawScope.drawFrameTube(a: Offset, b: Offset, width: Float) {
    drawLine(FRAME, a, b, width, StrokeCap.Round)
    // Thin highlight along the tube for a metallic read.
    drawLine(FRAME_HI, a, b, width * 0.32f, StrokeCap.Round)
}

private fun DrawScope.drawLeg(hip: Offset, pedal: Offset, bbY: Float, side: Float, scale: Float) {
    val footLift = (bbY - pedal.y)              // >0 when this foot is rising
    val mid = Offset((hip.x + pedal.x) * 0.5f, (hip.y + pedal.y) * 0.5f)
    val knee = Offset(
        mid.x + side * scale * 0.12f,
        mid.y - scale * 0.06f - footLift * 0.40f,
    )
    val thighShade = if (side < 0) SHORTS_DARK else SHORTS
    val calfShade = if (side < 0) SKIN_DARK else SKIN
    // Thigh (shorts) — tapered with two strokes.
    drawLine(thighShade, hip, knee, scale * 0.17f, StrokeCap.Round)
    drawLine(SHORTS_HI.copy(alpha = 0.5f), hip, knee, scale * 0.06f, StrokeCap.Round)
    // Calf (skin) tapering to the ankle.
    drawLine(calfShade, knee, pedal, scale * 0.12f, StrokeCap.Round)
    // Shoe.
    val shoe = Path().apply {
        moveTo(pedal.x - scale * 0.10f, pedal.y - scale * 0.03f)
        cubicTo(
            pedal.x - scale * 0.12f, pedal.y + scale * 0.05f,
            pedal.x + scale * 0.12f, pedal.y + scale * 0.05f,
            pedal.x + scale * 0.10f, pedal.y - scale * 0.03f,
        )
        close()
    }
    drawPath(shoe, SHOE)
    drawLine(SHOE_HI, Offset(pedal.x - scale * 0.07f, pedal.y - scale * 0.02f), Offset(pedal.x + scale * 0.07f, pedal.y - scale * 0.02f), scale * 0.02f, StrokeCap.Round)
}

private fun DrawScope.drawArm(shoulder: Offset, hand: Offset, side: Float, scale: Float) {
    val mid = Offset((shoulder.x + hand.x) * 0.5f, (shoulder.y + hand.y) * 0.5f)
    val elbow = Offset(mid.x + side * scale * 0.02f, mid.y + scale * 0.015f)
    // Upper arm (jersey sleeve) then forearm (skin).
    drawLine(JERSEY_MID, shoulder, elbow, scale * 0.052f, StrokeCap.Round)
    drawLine(if (side < 0) SKIN_DARK else SKIN, elbow, hand, scale * 0.044f, StrokeCap.Round)
    // Glove / hand on the bar.
    drawCircle(SHOE, scale * 0.026f, hand)
}
