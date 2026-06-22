package com.bike.trainer.ui.ride

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.BlurOff
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.session.RideStatus
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
    // Default to the Street View / ground view (real Street View when a Maps key
    // is present, otherwise the near-ground map view).
    var streetLevel by remember { mutableStateOf(true) }
    var smoothTransitions by remember { mutableStateOf(true) }
    var svMotion by remember { mutableStateOf(SvMotion.DOLLY) }
    var motionMenu by remember { mutableStateOf(false) }
    var sceneBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // Prefetched Street View frames for this route, if a valid cache exists.
    val svManifest = remember(engine.route.id) {
        com.bike.trainer.route.StreetViewCache.loadFor(context, engine.route)
    }

    suspend fun captureScene(): Boolean {
        val window = ScreenCapture.findActivity(context)?.window ?: return false
        val bmp = ScreenCapture.captureScene(window, ServiceLocator.sceneView, sceneBounds) ?: return false
        ServiceLocator.capturedRideImage = bmp
        return true
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

    // ---- 3D map scenery with grade + route overlay ----
    @Composable
    fun Scene(modifier: Modifier) {
        Box(
            modifier = modifier
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindow()
                    sceneBounds = android.graphics.Rect(
                        b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(),
                    )
                },
        ) {
            val googleStreet = streetLevel && com.bike.trainer.BuildConfig.HAS_MAPS_KEY
            if (googleStreet && svManifest != null) {
                // Prefetched frames: smooth, offline, no live panorama reloads.
                CachedStreetView(
                    manifest = svManifest,
                    distanceMeters = state.lapPositionMeters,
                    speedKmh = state.speedKmh,
                    mode = svMotion,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (googleStreet) {
                StreetViewScene(
                    route = engine.route,
                    distanceMeters = state.lapPositionMeters,
                    smoothTransitions = smoothTransitions,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MapSceneView(
                    route = engine.route,
                    distanceMeters = state.lapPositionMeters,
                    mapTilesKey = appConfig?.mapTilesKey.orEmpty(),
                    streetLevel = streetLevel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // ---- Top HUD: route + all live metrics, overlaid on the scene ----
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                                androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        ),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = engine.route.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HudStat("GRADE", String.format(Locale.US, "%+.1f%%", state.gradePercent), gradeColor(state.gradePercent))
                    HudStat("W", "${state.powerWatts}")
                    HudStat("KM/H", String.format(Locale.US, "%.1f", state.speedKmh))
                    HudStat("RPM", "${state.cadenceRpm}")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HudStat("KM", String.format(Locale.US, "%.2f", state.distanceMeters / 1000.0))
                    HudStat("TIME", formatTime(state.elapsedSeconds))
                    HudStat("ELEV", "${state.elevationMeters.toInt()}m")
                    HudStat("BPM", if (state.heartRate > 0) "${state.heartRate}" else "—")
                }
            }
            // Animated rider, shown only over Street View where it reads as a
            // real cyclist on the road (the chase/map view has its own framing).
            if (googleStreet) {
                CyclistView(
                    speedKmh = state.speedKmh,
                    cadenceRpm = state.cadenceRpm,
                    gradePercent = state.gradePercent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .fillMaxWidth(0.4f)
                        .height(115.dp),
                )
            }
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
                // Motion-style picker, only when playing prefetched frames.
                if (googleStreet && svManifest != null) {
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                .clickable { motionMenu = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Animation,
                                contentDescription = "Motion style",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "  Motion: ${svMotion.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(expanded = motionMenu, onDismissRequest = { motionMenu = false }) {
                            SvMotion.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.label) },
                                    onClick = { svMotion = m; motionMenu = false },
                                )
                            }
                        }
                    }
                }
                // Smooth-transition on/off, only for the live (non-cached) panorama.
                if (googleStreet && svManifest == null) {
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
                            val ok = captureScene()
                            Toast.makeText(
                                context,
                                if (ok) "Captured" else "Couldn't capture the view",
                                Toast.LENGTH_SHORT,
                            ).show()
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
    }

    // ---- Progress chart, gears, pause/finish (the bottom panel) ----
    @Composable
    fun Controls(modifier: Modifier) {
      Column(modifier) {
        Spacer(Modifier.height(10.dp))
        // ---- Elevation profile ----
        ElevationProfileView(
            route = engine.route,
            distanceMeters = state.lapPositionMeters,
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

        val paused = state.status == RideStatus.Paused
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { if (paused) engine.resume() else engine.pause() },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                )
                Text(if (paused) "  Resume" else "  Pause", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { engine.finish() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Finish", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
      }
    }

    // Landscape (tablets / rotated phones): scene on the left, controls scrolling
    // on the right. Portrait: scene on top, controls below.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            Scene(Modifier.weight(1.4f).fillMaxHeight())
            Controls(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            Scene(Modifier.fillMaxWidth().height(280.dp))
            Controls(Modifier.fillMaxWidth())
        }
    }
}

/** One compact stat in the top HUD: bold value over a small unit/label. */
@Composable
private fun HudStat(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

private val BikeYellow = androidx.compose.ui.graphics.Color(0xFFF2C20E)

private fun gradeColor(grade: Double) = when {
    grade > 6 -> com.bike.trainer.ui.theme.BikeRed
    grade > 3 -> com.bike.trainer.ui.theme.BikeOrange
    grade > 0 -> BikeYellow
    else -> com.bike.trainer.ui.theme.BikeGreen
}

private fun formatTime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
