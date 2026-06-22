package com.bike.trainer.ui.ride

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.bike.trainer.R

/**
 * The cyclist you're chasing, drawn from behind so it matches the forward-moving
 * map camera. It's a rendered sprite (PNG frames covering one pedal revolution)
 * animated by cycling through the frames — the cadence (or a speed estimate when
 * there's no trainer) sets how fast the legs spin, so the animation naturally
 * speeds up in harder/faster gears and slows on the climbs.
 *
 * Fully offline: the frames ship in res/drawable, no model asset or network.
 */
private val FRAMES = intArrayOf(
    R.drawable.cyclist_0,
    R.drawable.cyclist_1,
    R.drawable.cyclist_2,
    R.drawable.cyclist_3,
    R.drawable.cyclist_4,
    R.drawable.cyclist_5,
)

@Composable
fun CyclistView(
    speedKmh: Double,
    cadenceRpm: Int,
    gradePercent: Double,
    modifier: Modifier = Modifier,
) {
    val speed by rememberUpdatedState(speedKmh)
    val cadence by rememberUpdatedState(cadenceRpm)

    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var revolutions = 0f
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last).coerceAtMost(50_000_000L)) / 1_000_000_000f
                    val ms = (speed / 3.6).toFloat()
                    val cad = when {
                        cadence > 0 -> cadence.toFloat()
                        ms > 0.5f -> 80f
                        else -> 0f
                    }
                    revolutions += cad / 60f * dt          // crank revolutions
                    val t = revolutions - kotlin.math.floor(revolutions)
                    frame = (t * FRAMES.size).toInt().coerceIn(0, FRAMES.size - 1)
                }
                last = now
            }
        }
    }

    Image(
        painter = painterResource(FRAMES[frame]),
        contentDescription = "Cyclist",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
