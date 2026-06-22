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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.onPause()
            view.onDestroy()
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
        val hopMeters = if (smoothTransitions) SMOOTH_HOP_METERS else STEP_HOP_METERS
        if (abs(distanceMeters - lastPositionedAt) > hopMeters) {
            lastPositionedAt = distanceMeters
            // OUTDOOR restricts to official road-level Street View and drops
            // indoor panoramas and most user-contributed photo spheres.
            p.setPosition(LatLng(point.lat, point.lon), 120, StreetViewSource.OUTDOOR)
        }
        // Turn toward the route heading — keep the zoom fixed so the view doesn't
        // pulse forward-and-back between panorama hops.
        p.animateTo(
            StreetViewPanoramaCamera.Builder()
                .bearing(Math.toDegrees(point.heading).toFloat())
                .tilt(0f)
                .zoom(0f)
                .build(),
            if (smoothTransitions) 500L else 0L,
        )
    }
}

/** Frequent hops chain the SDK's own transitions into continuous forward motion. */
private const val SMOOTH_HOP_METERS = 9.0

/** Sparse hops jump the rider ahead in obvious discrete steps. */
private const val STEP_HOP_METERS = 32.0
