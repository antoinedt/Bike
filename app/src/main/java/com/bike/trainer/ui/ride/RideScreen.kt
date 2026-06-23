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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.bike.trainer.session.StepStatus
import com.bike.trainer.session.WorkoutLive
import com.bike.trainer.session.WorkoutStepLive
import com.bike.trainer.ui.theme.adherenceColor
import com.bike.trainer.ui.theme.workoutZoneColor
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
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
    var svMotion by remember { mutableStateOf(SvMotion.PARALLAX) }
    var motionMenu by remember { mutableStateOf(false) }
    // Advanced Street View motion tuning (Settings). Pick up the saved default mode
    // and knobs; the in-ride dropdown can still override the mode live.
    val svMotionMode = appConfig?.svMotionMode
    LaunchedEffect(svMotionMode) {
        svMotionMode?.let { name ->
            runCatching { SvMotion.valueOf(name) }.getOrNull()?.let { svMotion = it }
        }
    }
    val svParams = appConfig?.let {
        SvMotionParams(strength = it.svStrength, horizon = it.svHorizon, groundRush = it.svGroundRush)
    } ?: SvMotionParams()
    // Which ride-control buttons to show (Settings → Ride controls).
    val showMotion = appConfig?.showMotionControl ?: true
    val showView = appConfig?.showViewToggle ?: true
    val showCapture = appConfig?.showCaptureButton ?: true
    val workoutTol = (appConfig?.workoutTolerance ?: 0.10f).toDouble()
    var sceneBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // Prefetched Street View frames for this route, if a valid cache exists.
    val svManifest = remember(engine.route.id) {
        com.bike.trainer.route.StreetViewCache.loadFor(context, engine.route)
    }
    // Whether the live Google panorama is safe to show: cached frames are always
    // fine; for the live panorama we must confirm real coverage first, because the
    // Street View SDK can crash when driven to a point it has no imagery for.
    // null = still checking → show the map until we know.
    var svLiveAvailable by remember(engine.route.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(engine.route.id) {
        svLiveAvailable = when {
            svManifest != null -> true
            !com.bike.trainer.BuildConfig.HAS_MAPS_KEY -> false
            else -> {
                val p = engine.route.pointAt(engine.state.value.lapPositionMeters)
                com.bike.trainer.route.StreetViewCoverage.hasCoverage(
                    p.lat, p.lon, com.bike.trainer.BuildConfig.MAPS_API_KEY,
                )
            }
        }
    }
    // Let the rider know once why a route is on the map rather than Street View.
    LaunchedEffect(svLiveAvailable) {
        if (svLiveAvailable == false && svManifest == null &&
            com.bike.trainer.BuildConfig.HAS_MAPS_KEY
        ) {
            Toast.makeText(
                context,
                "No Street View along this route — showing the map instead.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    // "Google street view is on screen": street toggle + key + (cached OR confirmed
    // live coverage). Until the coverage check resolves we keep this false so the
    // map shows and the crash-prone panorama is never created.
    val googleStreet = streetLevel && com.bike.trainer.BuildConfig.HAS_MAPS_KEY &&
        (svManifest != null || svLiveAvailable == true)

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
            if (googleStreet && svManifest != null) {
                // Prefetched frames: smooth, offline, no live panorama reloads.
                CachedStreetView(
                    manifest = svManifest,
                    distanceMeters = state.lapPositionMeters,
                    speedKmh = state.speedKmh,
                    mode = svMotion,
                    modifier = Modifier.fillMaxSize(),
                    params = svParams,
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
                val wo = state.workout
                val powerColor = if (wo != null && wo.activeIndex >= 0) {
                    adherenceColor(state.powerWatts, wo.targetWatts, workoutTol)
                } else {
                    androidx.compose.ui.graphics.Color.White
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HudStat("GRADE", String.format(Locale.US, "%+.1f%%", state.gradePercent), gradeColor(state.gradePercent))
                    HudStat(if (wo != null && wo.activeIndex >= 0) "W → ${wo.targetWatts}" else "W", "${state.powerWatts}", powerColor)
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
            // Structured-workout step list, down the left edge (scrollable).
            state.workout?.let { wo ->
                WorkoutStepsPanel(
                    workout = wo,
                    currentPower = state.powerWatts,
                    tolerance = workoutTol,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp, top = 4.dp, bottom = 4.dp)
                        .fillMaxHeight(0.74f)
                        .width(96.dp),
                )
            }
        }
    }

    // ---- Progress chart, gears, pause/finish (the bottom panel) ----
    @Composable
    fun Controls(modifier: Modifier) {
      Column(modifier) {
        Spacer(Modifier.height(6.dp))

        // ---- Scene controls: motion / smooth, view toggle, capture ----
        val showMotionChip = showMotion && googleStreet
        if (showMotionChip || showView || showCapture) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showMotionChip && svManifest != null) {
                    Box {
                        ControlChip(Icons.Filled.Animation, "Motion: ${svMotion.label}") { motionMenu = true }
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
                if (showMotionChip && svManifest == null) {
                    ControlChip(
                        if (smoothTransitions) Icons.Filled.BlurOn else Icons.Filled.BlurOff,
                        if (smoothTransitions) "Smooth: On" else "Smooth: Off",
                    ) { smoothTransitions = !smoothTransitions }
                }
                if (showView) {
                    val toggleLabel = when {
                        !streetLevel -> "Chase"
                        com.bike.trainer.BuildConfig.HAS_MAPS_KEY -> "Street View"
                        else -> "Ground"
                    }
                    ControlChip(Icons.Filled.Layers, toggleLabel) { streetLevel = !streetLevel }
                }
                if (showCapture) {
                    Spacer(Modifier.weight(1f))
                    FilledIconButton(
                        onClick = {
                            scope.launch {
                                val ok = captureScene()
                                Toast.makeText(
                                    context,
                                    if (ok) "Captured" else "Couldn't capture the view",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
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
            Spacer(Modifier.height(10.dp))
        }

        // ---- Elevation profile ----
        ElevationProfileView(
            route = engine.route,
            distanceMeters = state.lapPositionMeters,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp),
        )

        Spacer(Modifier.height(6.dp))

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
                modifier = Modifier.size(56.dp),
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
                    fontSize = 32.sp,
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
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Shift up (harder)")
            }
        }

        Spacer(Modifier.height(8.dp))

        val paused = state.status == RideStatus.Paused
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = { if (paused) engine.resume() else engine.pause() },
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "Resume" else "Pause",
                )
            }
            Button(
                onClick = { engine.finish() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Finish", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
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
            // Give the course most of the width; the controls get a slim,
            // scrollable column on the side.
            Scene(Modifier.weight(2.4f).fillMaxHeight())
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
            // Scene fills all the space the controls don't need, so the course gets
            // the majority while the controls stay fully on-screen (above the nav
            // bar) at their natural, compact height — no scrolling, nothing hidden.
            Scene(Modifier.fillMaxWidth().weight(1f))
            Controls(Modifier.fillMaxWidth())
        }
    }

    // One-time "workout complete" popup; the ride then carries on as a free ride.
    var workoutDoneHandled by remember { mutableStateOf(false) }
    var showWorkoutDone by remember { mutableStateOf(false) }
    val completed = state.workout?.completed == true
    LaunchedEffect(completed) {
        if (completed && !workoutDoneHandled) {
            workoutDoneHandled = true
            showWorkoutDone = true
        }
    }
    if (showWorkoutDone) {
        val score = state.workout?.overallScore ?: 0
        AlertDialog(
            onDismissRequest = { showWorkoutDone = false },
            title = { Text("Workout complete!") },
            text = {
                Text("You scored $score / 100.\n\nRiding on as a free ride — finish whenever you're ready.")
            },
            confirmButton = {
                Button(onClick = { showWorkoutDone = false }) { Text("Keep riding") }
            },
        )
    }
}

/** A compact labelled chip used for the in-ride scene controls. */
@Composable
private fun ControlChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "  $label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Scrollable list of a workout's steps, shown down the left edge during a ride. */
@Composable
private fun WorkoutStepsPanel(
    workout: WorkoutLive,
    currentPower: Int,
    tolerance: Double,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(workout.activeIndex) {
        if (workout.activeIndex >= 0) {
            runCatching { listState.animateScrollToItem(workout.activeIndex) }
        }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(4.dp),
    ) {
        // Header: workout name + live overall score.
        Text(
            workout.name,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (workout.completed) {
            Text(
                "Done · ${workout.overallScore}/100",
                color = BikeYellow,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(3.dp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(workout.steps) { s -> WorkoutStepRow(s, currentPower, tolerance) }
        }
    }
}

/** One workout step: target/avg watts over its duration (counts down when active). */
@Composable
private fun WorkoutStepRow(s: WorkoutStepLive, currentPower: Int, tolerance: Double) {
    val active = s.status == StepStatus.ACTIVE
    val done = s.status == StepStatus.DONE
    // Active step: colour by how close the current power is to target.
    val bg = when {
        active -> adherenceColor(currentPower, s.targetWatts, tolerance)
        done -> Color.White.copy(alpha = 0.14f)
        else -> workoutZoneColor(s.ftpFraction).copy(alpha = 0.30f)
    }
    val fg = if (active) Color.Black else Color.White.copy(alpha = if (done) 0.65f else 0.92f)
    val seconds = if (active) s.remainingSeconds else s.seconds
    val watts = if (done) s.avgWatts else s.targetWatts
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        Text(
            "$watts W",
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            if (done) "${formatTime(seconds.toLong())} · ${s.scorePct}" else formatTime(seconds.toLong()),
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
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
