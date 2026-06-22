package com.bike.trainer.ui.ride

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.route.StreetViewManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays the prefetched Street View frames for a route. To feel like motion rather
 * than a slideshow, each frame slowly **dollies forward** (a continuous zoom-in)
 * across the segment, and the next frame is layered **on top** of the current one
 * and faded in — so there's no luminance dip (the old crossfade faded both frames
 * through transparency, which let the black background show through as a dark
 * flash). Fully offline.
 */
@Composable
fun CachedStreetView(
    manifest: StreetViewManifest,
    distanceMeters: Double,
    speedKmh: Double,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Nearest cached frame; recomputed only when the chosen file changes.
    val path = remember(manifest, bucket(distanceMeters)) {
        StreetViewCache.imageFileAt(context, manifest, distanceMeters)?.path
    }

    // Decode off the UI thread; produceState keeps the last value until the new
    // one is ready (no blank frame).
    val decoded by produceState<ImageBitmap?>(initialValue = null, path) {
        path?.let {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull()
            }
            if (bmp != null) value = bmp
        }
    }

    // front = the frame on top (fading/dollying in); back = the one beneath it.
    var front by remember { mutableStateOf<ImageBitmap?>(null) }
    var back by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }      // front opacity 0→1
    val zoom = remember { Animatable(1f) }       // front scale 1.0→DOLLY

    // How long the rider takes to cross one sample spacing at the current speed.
    val segmentMs = run {
        val speedMs = (speedKmh / 3.6).coerceAtLeast(0.3)
        (manifest.spacingMeters / speedMs * 1000.0).toInt().coerceIn(400, 6000)
    }

    LaunchedEffect(decoded) {
        val bmp = decoded ?: return@LaunchedEffect
        if (bmp === front) return@LaunchedEffect
        val first = front == null
        if (!first) back = front
        front = bmp
        // Start the new frame un-zoomed; layer it over the old one and fade it in
        // (no luminance dip), then dolly forward across the whole segment.
        zoom.snapTo(1f)
        fade.snapTo(if (first) 1f else 0f)
        val dissolveMs = (segmentMs / 3).coerceIn(180, 600)
        coroutineScope {
            if (!first) launch { fade.animateTo(1f, tween(dissolveMs, easing = LinearEasing)) }
            launch { zoom.animateTo(DOLLY, tween(segmentMs, easing = LinearEasing)) }
        }
    }

    Box(modifier.background(Color.Black).clipToBounds()) {
        back?.let { b ->
            Image(
                bitmap = b,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = DOLLY; scaleY = DOLLY },
                contentScale = ContentScale.Crop,
            )
        }
        front?.let { f ->
            Image(
                bitmap = f,
                contentDescription = "Street View",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                        alpha = fade.value
                    },
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Forward dolly: how much each frame zooms in across one segment. */
private const val DOLLY = 1.14f

/** Bucket the distance so we only re-pick a frame every ~2 m, not every tick. */
private fun bucket(distanceMeters: Double): Long = (distanceMeters / 2.0).toLong()
