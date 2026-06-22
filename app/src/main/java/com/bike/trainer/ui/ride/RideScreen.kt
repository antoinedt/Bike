package com.bike.trainer.ui.ride

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BlurOff
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.session.RideStatus
import com.bike.trainer.ui.components.StatTile
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun RideScreen(
    onFinished: () -> Unit,
    onExit: () -> Unit,
) {
    val engine = ServiceLocator.activeRide
    if (engine == null) {
        // No active ride (e.g. process restart) — bail back home.
        LaunchedEffect(Unit) { onExit() }
        return
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by engine.state.collectAsStateWithLifecycle()
    val appConfig by ServiceLocator.appConfigRepository.config
        .collectAsStateWithLifecycle(initialValue = null)
    var streetLevel by remember { mutableStateOf(false) }
    var smoothTransitions by remember { mutableStateOf(true) }
    var sceneBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }

    suspend fun captureScene() {
        val window = ScreenCapture.findActivity(context)?.window ?: return
        val rect = sceneBounds ?: return
        ScreenCapture.capture(window, rect)?.let { ServiceLocator.capturedRideImage = it }
    }

    LaunchedEffect(Unit) {
        if (state.status == RideStatus.NotStarted) {
            ServiceLocator.capturedRideImage = null
            engine.start(scope)
        }
    }
    // Auto-capture a frame periodically as a recap fallback.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000)
            if (state.status == RideStatus.Running) captureScene()
        }
    }
    LaunchedEffect(state.status) {
        if (state.status == RideStatus.Finished) onFinished()
    }
    // Apply shifts from a connected physical Zwift controller.
    LaunchedEffect(Unit) {
        ServiceLocator.zwiftClickManager.gearEvents.collect { shift ->
            when (shift) {
                com.bike.trainer.ble.GearShift.UP -> engine.shiftUp()
                com.bike.trainer.ble.GearShift.DOWN -> engine.shiftDown()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        // ---- 3D map scenery with grade + route overlay ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindow()
                    sceneBounds = android.graphics.Rect(
                        b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(),
                    )
                },
        ) {
            val googleStreet = streetLevel && com.bike.trainer.BuildConfig.HAS_MAPS_KEY
            if (googleStreet) {
                StreetViewScene(
                    route = engine.route,
                    distanceMeters = state.distanceMeters,
                    smoothTransitions = smoothTransitions,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MapSceneView(
                    route = engine.route,
                    distanceMeters = state.distanceMeters,
                    mapTilesKey = appConfig?.mapTilesKey.orEmpty(),
                    streetLevel = streetLevel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Grade badge.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = String.format(Locale.US, "%+.1f%%", state.gradePercent),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = gradeColor(state.gradePercent),
                )
            }
            // Route name.
            Text(
                text = engine.route.name,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Animated rider that pedals with speed and stands up on climbs.
            // Scaled smaller over Street View so it sits believably on the road.
            CyclistView(
                speedKmh = state.speedKmh,
                cadenceRpm = state.cadenceRpm,
                gradePercent = state.gradePercent,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (googleStreet) 10.dp else 6.dp)
                    .fillMaxWidth(if (googleStreet) 0.4f else 0.6f)
                    .height(if (googleStreet) 115.dp else 150.dp),
            )
            // Camera view toggle: chase <-> street.
            val toggleLabel = when {
                !streetLevel -> "  Chase"
                com.bike.trainer.BuildConfig.HAS_MAPS_KEY -> "  Street View"
                else -> "  Ground"
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Smooth-transition on/off, only while Street View is active.
                if (googleStreet) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .clickable { smoothTransitions = !smoothTransitions }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (smoothTransitions) Icons.Filled.BlurOn else Icons.Filled.BlurOff,
                            contentDescription = "Toggle smooth transitions",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            if (smoothTransitions) "  Smooth: On" else "  Smooth: Off",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable { streetLevel = !streetLevel }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Layers,
                        contentDescription = "Toggle view",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        toggleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Manual capture button for the recap photo.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .clickable {
                        scope.launch {
                            captureScene()
                            Toast.makeText(context, "Captured", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(10.dp),
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = "Capture photo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // ---- Primary metrics ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatTile("Power", "${state.powerWatts} W", accent = true)
            StatTile("Speed", String.format(Locale.US, "%.1f", state.speedKmh) + " km/h")
            StatTile("Cadence", "${state.cadenceRpm} rpm")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatTile("Distance", String.format(Locale.US, "%.2f km", state.distanceMeters / 1000.0))
            StatTile("Time", formatTime(state.elapsedSeconds))
            StatTile("Elev", "${state.elevationMeters.toInt()} m")
            StatTile("HR", if (state.heartRate > 0) "${state.heartRate} bpm" else "—")
        }

        // ---- Elevation profile ----
        ElevationProfileView(
            route = engine.route,
            distanceMeters = state.distanceMeters,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .padding(horizontal = 12.dp),
        )

        Spacer(Modifier.height(8.dp))

        // ---- Gears ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = { engine.shiftDown() },
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Shift down (easier)")
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("GEAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${state.gear}",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "of ${state.gearCount}  ·  ${String.format(Locale.US, "%.2f", state.gearRatio)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilledIconButton(
                onClick = { engine.shiftUp() },
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Shift up (harder)")
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { engine.finish() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Finish Ride", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun gradeColor(grade: Double) = when {
    grade > 3 -> com.bike.trainer.ui.theme.BikeRed
    grade < -1 -> com.bike.trainer.ui.theme.BikeGreen
    else -> com.bike.trainer.ui.theme.BikeOrange
}

private fun formatTime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
