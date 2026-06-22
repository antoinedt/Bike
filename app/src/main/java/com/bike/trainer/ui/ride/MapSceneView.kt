package com.bike.trainer.ui.ride

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bike.trainer.map.MapStyle
import com.bike.trainer.route.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The 3D ride scenery: a real MapLibre map (satellite + 3D terrain + extruded
 * OSM buildings when a MapTiler key is present) with a tilted chase camera that
 * follows the rider along the route. Gestures are disabled — it's a passive
 * cinematic view driven entirely by the ride engine.
 */
@Composable
fun MapSceneView(
    route: Route,
    distanceMeters: Double,
    mapTilesKey: String,
    streetLevel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var riderSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var ready by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { _ ->
            mapView.getMapAsync { libreMap ->
                libreMap.uiSettings.apply {
                    setAllGesturesEnabled(false)
                    isCompassEnabled = false
                }
                // Allow the near-horizontal pitch used by the street-level view.
                libreMap.setMaxPitchPreference(85.0)
                val start = route.pointAt(0.0)
                libreMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(start.lat, start.lon))
                    .zoom(CHASE_ZOOM)
                    .tilt(CHASE_TILT)
                    .bearing(Math.toDegrees(start.heading))
                    .build()
                map = libreMap
            }
            mapView
        },
    )

    // (Re)load the style whenever the map is ready OR the MapTiler key changes.
    // The saved key loads asynchronously, so this catches the moment it arrives
    // and swaps the flat demo tiles for the real 3D MapTiler style.
    LaunchedEffect(map, mapTilesKey) {
        val m = map ?: return@LaunchedEffect
        ready = false
        val builder = if (mapTilesKey.isNotBlank()) {
            Style.Builder().fromJson(MapStyle.styleJson(mapTilesKey))
        } else {
            Style.Builder().fromUri(MapStyle.DEMO_STYLE_URL)
        }
        m.setStyle(builder) { style ->
            addRouteLine(style, route)
            val start = route.pointAt(0.0)
            val rider = GeoJsonSource(RIDER_SOURCE, pointGeoJson(start.lat, start.lon))
            style.addSource(rider)
            style.addLayer(
                CircleLayer(RIDER_LAYER, RIDER_SOURCE).withProperties(
                    PropertyFactory.circleColor(Color.parseColor("#FF7A1A")),
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2.5f),
                )
            )
            riderSource = rider
            ready = true
        }
    }

    // Follow the rider every time the ride advances (or the view mode changes).
    LaunchedEffect(ready, distanceMeters, streetLevel) {
        if (!ready) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val p = route.pointAt(distanceMeters)
        riderSource?.setGeoJson(pointGeoJson(p.lat, p.lon))
        val zoom = if (streetLevel) STREET_ZOOM else CHASE_ZOOM
        val tilt = if (streetLevel) STREET_TILT else CHASE_TILT
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(p.lat, p.lon))
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(Math.toDegrees(p.heading))
                    .build()
            ),
            220,
        )
    }
}

private const val ROUTE_SOURCE = "route-source"
private const val ROUTE_LAYER = "route-layer"
private const val RIDER_SOURCE = "rider-source"
private const val RIDER_LAYER = "rider-layer"

// Camera presets: an angled chase view, and a near-ground forward "street" view.
private const val CHASE_ZOOM = 16.5
private const val CHASE_TILT = 62.0
private const val STREET_ZOOM = 19.5
private const val STREET_TILT = 85.0

private fun addRouteLine(style: Style, route: Route) {
    val points = route.points
    if (points.size < 2) return
    // Downsample so the line stays light even for very long routes.
    val step = (points.size / 1500).coerceAtLeast(1)
    val coords = StringBuilder()
    var i = 0
    var first = true
    while (i < points.size) {
        if (!first) coords.append(',')
        val p = points[i]
        coords.append('[').append(p.lon).append(',').append(p.lat).append(']')
        first = false
        i += step
    }
    val geoJson = """
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}
    """.trimIndent()

    style.addSource(GeoJsonSource(ROUTE_SOURCE, geoJson))
    style.addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#FF7A1A")),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.9f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
    )
}

private fun pointGeoJson(lat: Double, lon: Double): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""

/**
 * Creates a [MapView] and drives its lifecycle from the current Compose
 * lifecycle owner, so the map starts/stops/destroys correctly.
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        // MapView needs onCreate before any other call.
        MapView(context).apply { onCreate(null) }
    }

    val lifecycle = lifecycleOwner.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // We may enter composition already started/resumed.
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
