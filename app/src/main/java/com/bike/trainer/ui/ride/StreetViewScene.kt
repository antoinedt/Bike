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
import kotlin.math.abs

/**
 * Interactive Google Street View panorama that follows the ride. Requires a
 * Google Maps API key in the manifest (build-time MAPS_API_KEY). The panorama
 * is re-positioned as the rider moves and the camera bearing tracks the route
 * heading; gaps in Street View coverage show a small notice.
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
                    p.isStreetNamesEnabled = true
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

    LaunchedEffect(panorama, distanceMeters) {
        val p = panorama ?: return@LaunchedEffect
        val point = route.pointAt(distanceMeters)
        // Loading a new panorama is costly, so only re-anchor every ~15 m.
        if (abs(distanceMeters - lastPositionedAt) > 15.0) {
            lastPositionedAt = distanceMeters
            p.setPosition(LatLng(point.lat, point.lon), 120)
        }
        p.animateTo(
            StreetViewPanoramaCamera.Builder()
                .bearing(Math.toDegrees(point.heading).toFloat())
                .tilt(0f)
                .zoom(0f)
                .build(),
            300L,
        )
    }
}
