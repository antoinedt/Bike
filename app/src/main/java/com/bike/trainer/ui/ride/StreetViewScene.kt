package com.bike.trainer.ui.ride

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * The widget only ever shows one panorama, so to make movement feel continuous
 * rather than jumping every time we load a new one, we: ramp a slight forward
 * zoom between hops (a sense of moving into the scene), then mask the panorama
 * swap with a quick crossfade/dissolve, while the camera bearing animates
 * smoothly toward the route heading.
 */
@Composable
fun StreetViewScene(
    route: Route,
    distanceMeters: Double,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember { StreetViewPanoramaView(context).apply { onCreate(null) } }

    var panorama by remember { mutableStateOf<StreetViewPanorama?>(null) }
    var hasCoverage by remember { mutableStateOf(true) }
    var lastPositionedAt by remember { mutableDoubleStateOf(-1000.0) }
    var hops by remember { mutableIntStateOf(0) }
    val dissolve = remember { Animatable(0f) }

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

        // Dissolve scrim that briefly covers the hard panorama swap.
        if (dissolve.value > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = dissolve.value)),
            )
        }

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

    // Animate the dissolve each time we hop to a new panorama.
    LaunchedEffect(hops) {
        if (hops > 0) {
            dissolve.snapTo(0.55f)
            dissolve.animateTo(0f, tween(durationMillis = 320))
        }
    }

    // Track position/heading every tick; re-anchor (and dissolve) every ~12 m.
    LaunchedEffect(panorama, distanceMeters) {
        val p = panorama ?: return@LaunchedEffect
        val point = route.pointAt(distanceMeters)
        if (abs(distanceMeters - lastPositionedAt) > HOP_METERS) {
            lastPositionedAt = distanceMeters
            hops += 1
            // OUTDOOR restricts to official road-level Street View and drops
            // indoor panoramas and most user-contributed photo spheres.
            p.setPosition(LatLng(point.lat, point.lon), 120, StreetViewSource.OUTDOOR)
        }
        // Forward zoom builds across the segment to fake moving into the scene.
        val progress = ((distanceMeters - lastPositionedAt) / HOP_METERS).coerceIn(0.0, 1.0)
        p.animateTo(
            StreetViewPanoramaCamera.Builder()
                .bearing(Math.toDegrees(point.heading).toFloat())
                .tilt(0f)
                .zoom((progress * 0.6).toFloat())
                .build(),
            260L,
        )
    }
}

private const val HOP_METERS = 12.0
