package com.bike.trainer.ui.ride

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bike.trainer.route.Route
import com.bike.trainer.ui.theme.BikeOrange

/**
 * Side-on elevation profile of the whole route with a marker showing where the
 * rider currently is. The portion already ridden is filled in the accent colour.
 */
@Composable
fun ElevationProfileView(
    route: Route,
    distanceMeters: Double,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawProfile(route, distanceMeters)
    }
}

private fun DrawScope.drawProfile(route: Route, distance: Double) {
    val points = route.points
    if (points.size < 2) return

    val w = size.width
    val h = size.height
    val pad = h * 0.12f

    val minE = route.minElevation
    val maxE = route.maxElevation
    val range = (maxE - minE).coerceAtLeast(1.0)

    fun x(d: Double): Float = (d / route.totalDistance * w).toFloat()
    fun y(e: Double): Float = (h - pad - ((e - minE) / range) * (h - 2 * pad)).toFloat()

    // Build the elevation line, sampling a manageable number of points.
    val sampleCount = 160
    val linePath = Path()
    val fillRiddenPath = Path()
    fillRiddenPath.moveTo(0f, h)

    for (i in 0..sampleCount) {
        val d = route.totalDistance * i / sampleCount
        val px = x(d)
        val py = y(route.elevationAt(d))
        if (i == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
    }

    // Filled "already ridden" region up to the current distance.
    fillRiddenPath.lineTo(0f, y(route.elevationAt(0.0)))
    var d = 0.0
    val stepD = route.totalDistance / sampleCount
    while (d <= distance && d <= route.totalDistance) {
        fillRiddenPath.lineTo(x(d), y(route.elevationAt(d)))
        d += stepD
    }
    fillRiddenPath.lineTo(x(distance.coerceIn(0.0, route.totalDistance)), h)
    fillRiddenPath.close()

    drawPath(fillRiddenPath, color = BikeOrange.copy(alpha = 0.30f))
    drawPath(linePath, color = Color(0xFFB9C6D2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

    // Current position marker.
    val markerX = x(distance.coerceIn(0.0, route.totalDistance))
    val markerY = y(route.elevationAt(distance))
    drawLine(
        color = BikeOrange,
        start = Offset(markerX, pad * 0.4f),
        end = Offset(markerX, h),
        strokeWidth = 2f,
    )
    drawCircle(color = BikeOrange, radius = 7f, center = Offset(markerX, markerY))
}
