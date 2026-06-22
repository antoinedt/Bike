package com.bike.trainer.ui.ride

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bike.trainer.route.Route
import com.bike.trainer.ui.theme.BikeGreen
import com.bike.trainer.ui.theme.BikeSky
import kotlin.math.PI
import kotlin.math.sin

/**
 * A lightweight pseudo-3D view down the corridor: a road receding to a vanishing
 * point that bends with the upcoming route and tilts with the gradient. Purely
 * decorative — the simulation lives in the engine — but it gives the ride a
 * sense of motion and terrain.
 */
@Composable
fun CorridorView(
    route: Route,
    distanceMeters: Double,
    gradePercent: Double,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawCorridor(route, distanceMeters, gradePercent)
    }
}

private fun DrawScope.drawCorridor(route: Route, distance: Double, gradePercent: Double) {
    val w = size.width
    val h = size.height

    // Horizon rises when descending and drops when climbing, exaggerating grade.
    val gradeShift = (gradePercent / 12.0).coerceIn(-1.0, 1.0) * (h * 0.12)
    val horizonY = (h * 0.42 + gradeShift).toFloat()

    // Sky gradient.
    drawRect(
        color = BikeSky.copy(alpha = 0.9f),
        topLeft = Offset(0f, 0f),
        size = androidx.compose.ui.geometry.Size(w, horizonY),
    )
    // Ground.
    drawRect(
        color = Color(0xFF12351F),
        topLeft = Offset(0f, horizonY),
        size = androidx.compose.ui.geometry.Size(w, h - horizonY),
    )

    val currentHeading = route.pointAt(distance).heading
    val lookAhead = 180.0
    val slices = 24

    val centerBottom = w / 2f
    val roadHalfBottom = w * 0.42f
    val roadHalfTop = w * 0.02f

    fun edgeAt(z: Float, side: Int): Offset {
        // z: 0 near (bottom) .. 1 far (horizon)
        val ahead = z * z * lookAhead
        val headingAhead = route.pointAt(distance + ahead).heading
        var diff = headingAhead - currentHeading
        // Normalise to [-PI, PI].
        while (diff > PI) diff -= 2 * PI
        while (diff < -PI) diff += 2 * PI
        val bend = sin(diff) * w * 0.9f * z * z
        val center = centerBottom + bend.toFloat()
        val half = roadHalfBottom + (roadHalfTop - roadHalfBottom) * z
        val x = center + side * half
        val y = h - z * (h - horizonY)
        return Offset(x, y)
    }

    // Road surface as a filled ribbon.
    val road = Path().apply {
        moveTo(edgeAt(0f, -1).x, edgeAt(0f, -1).y)
        var z = 0f
        val step = 1f / slices
        while (z <= 1f) { val p = edgeAt(z, -1); lineTo(p.x, p.y); z += step }
        z = 1f
        while (z >= 0f) { val p = edgeAt(z, +1); lineTo(p.x, p.y); z -= step }
        close()
    }
    drawPath(road, color = Color(0xFF2C3540))

    // Edge lines.
    drawRoadLine(::edgeAt, slices, -1, BikeGreen.copy(alpha = 0.7f), 3f)
    drawRoadLine(::edgeAt, slices, +1, BikeGreen.copy(alpha = 0.7f), 3f)

    // Scrolling dashed centre line.
    val dashPhase = (distance % 20.0) / 20.0
    var z = 0f
    val step = 1f / slices
    while (z < 1f) {
        val isDash = (((z * slices).toInt() + (dashPhase * 2).toInt()) % 2 == 0)
        if (isDash) {
            val a = centerLine(::edgeAt, z)
            val b = centerLine(::edgeAt, (z + step).coerceAtMost(1f))
            drawLine(
                color = Color(0xFFE8C24A).copy(alpha = (1f - z) * 0.9f),
                start = a,
                end = b,
                strokeWidth = (6f * (1f - z)).coerceAtLeast(1.5f),
            )
        }
        z += step
    }
}

private fun centerLine(edgeAt: (Float, Int) -> Offset, z: Float): Offset {
    val l = edgeAt(z, -1)
    val r = edgeAt(z, +1)
    return Offset((l.x + r.x) / 2f, (l.y + r.y) / 2f)
}

private fun DrawScope.drawRoadLine(
    edgeAt: (Float, Int) -> Offset,
    slices: Int,
    side: Int,
    color: Color,
    width: Float,
) {
    var z = 0f
    val step = 1f / slices
    var prev = edgeAt(z, side)
    while (z <= 1f) {
        val cur = edgeAt(z, side)
        drawLine(color, prev, cur, strokeWidth = width)
        prev = cur
        z += step
    }
}
