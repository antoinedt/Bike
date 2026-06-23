package com.bike.trainer.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bike.trainer.route.Route
import com.google.android.gms.maps.StreetViewPanorama
import com.google.android.gms.maps.StreetViewPanoramaView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.StreetViewPanoramaCamera
import com.google.android.gms.maps.model.StreetViewSource
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive Google Street View panorama that follows the ride. Requires a
 * Google Maps API key in the manifest (build-time MAPS_API_KEY).
 *
 * The widget only ever shows one panorama, so we re-anchor it along the route as
 * the rider advances and let the SDK's own transition carry the swap. We no
 * longer overlay a dark scrim between hops (that produced a regular black
 * "blink"); [smoothTransitions] now just chooses whether the camera bearing
 * eases toward the new heading or snaps to it.
 */
@Composable
fun StreetViewScene(
    route: Route,
    distanceMeters: Double,
    smoothTransitions: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember { StreetViewPanoramaView(context).apply { onCreate(null) } }

    var panorama by remember { mutableStateOf<StreetViewPanorama?>(null) }
    var hasCoverage by remember { mutableStateOf(true) }
    var lastPositionedAt by remember { mutableDoubleStateOf(-1000.0) }
    // The forward bearing (degrees from north) we want the camera to hold. Kept in
    // a state holder so the panorama-change listener can re-apply it the moment a
    // new pano loads — otherwise the SDK shows the camera-car's orientation and the
    // view appears to spin.
    val desiredBearing = remember { mutableFloatStateOf(0f) }
    var lastAppliedBearing by remember { mutableFloatStateOf(Float.NaN) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.onResume()
        // Expose this GL view so the screenshot can copy its surface directly.
        com.bike.trainer.di.ServiceLocator.sceneView = view
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.onPause()
            view.onDestroy()
            if (com.bike.trainer.di.ServiceLocator.sceneView === view) {
                com.bike.trainer.di.ServiceLocator.sceneView = null
            }
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                view.getStreetViewPanoramaAsync { p ->
                    p.isUserNavigationEnabled = false
                    p.isStreetNamesEnabled = false
                    p.isPanningGesturesEnabled = false
                    p.isZoomGesturesEnabled = false
                    p.setOnStreetViewPanoramaChangeListener { location ->
                        hasCoverage = location != null
                        // A new panorama just loaded — snap it to face forward so it
                        // doesn't briefly show the Street View car's heading and then
                        // rotate toward our target. Guarded: the native SDK can throw
                        // when a route wanders off Street View coverage.
                        if (location != null) {
                            runCatching { p.animateTo(forwardCamera(desiredBearing.floatValue), 0L) }
                        }
                    }
                    panorama = p
                }
                view
            },
        )

        if (!hasCoverage) {
            Text(
                "No Street View imagery near here",
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    // Re-anchor the panorama along the route as we advance. The Street View SDK
    // plays its own short pano-to-pano transition on setPosition, so [smoothTransitions]
    // controls how *often* we hop: short, frequent hops chain those transitions into
    // continuous forward motion; long hops jump the rider ahead in discrete steps.
    LaunchedEffect(panorama, distanceMeters, smoothTransitions) {
        val p = panorama ?: return@LaunchedEffect
        val point = route.pointAt(distanceMeters)
        // Forward bearing from a look-ahead point, not the jittery per-segment
        // heading — this keeps the camera pointing steadily down the road instead
        // of swinging with every bend/switchback.
        val bearing = forwardBearing(route, distanceMeters)
        desiredBearing.floatValue = bearing
        val hopMeters = if (smoothTransitions) SMOOTH_HOP_METERS else STEP_HOP_METERS
        if (abs(distanceMeters - lastPositionedAt) > hopMeters) {
            lastPositionedAt = distanceMeters
            // OUTDOOR restricts to official road-level Street View and drops
            // indoor panoramas and most user-contributed photo spheres. Guarded:
            // remote/off-road routes can make the native SDK throw.
            runCatching { p.setPosition(LatLng(point.lat, point.lon), 120, StreetViewSource.OUTDOOR) }
        }
        // Only re-aim when the forward bearing actually moves (> 2°) AND we actually
        // have a panorama loaded — animating an empty panorama can crash the SDK.
        if (hasCoverage && (lastAppliedBearing.isNaN() || abs(angleDelta(bearing, lastAppliedBearing)) > 2f)) {
            lastAppliedBearing = bearing
            runCatching { p.animateTo(forwardCamera(bearing), if (smoothTransitions) 300L else 0L) }
        }
    }
}

/** A forward-facing, flat, fixed-zoom camera at [bearing] (degrees from north). */
private fun forwardCamera(bearing: Float): StreetViewPanoramaCamera =
    StreetViewPanoramaCamera.Builder()
        .bearing(bearing)
        .tilt(0f)
        .zoom(0f)
        .build()

/**
 * Stable "down the road" bearing in degrees from north, taken from the geographic
 * direction to a point [LOOKAHEAD_METERS] further along the route. Averaging over a
 * short distance smooths out the wiggle that made the panorama spin.
 */
private fun forwardBearing(route: Route, distance: Double): Float {
    val a = route.pointAt(distance)
    val b = route.pointAt((distance + LOOKAHEAD_METERS).coerceAtMost(route.totalDistance))
    val deg = if (a.lat == b.lat && a.lon == b.lon) {
        Math.toDegrees(a.heading)
    } else {
        val phi1 = Math.toRadians(a.lat)
        val phi2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        Math.toDegrees(atan2(y, x))
    }
    return (((deg % 360.0) + 360.0) % 360.0).toFloat()
}

/** Smallest signed difference between two bearings (degrees), in (-180, 180]. */
private fun angleDelta(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** How far ahead to look when deriving the steady forward bearing. */
private const val LOOKAHEAD_METERS = 30.0

/** Frequent hops chain the SDK's own transitions into continuous forward motion. */
private const val SMOOTH_HOP_METERS = 9.0

/** Sparse hops jump the rider ahead in obvious discrete steps. */
private const val STEP_HOP_METERS = 32.0
