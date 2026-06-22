package com.bike.trainer.ui.ride

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.route.StreetViewManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays the prefetched Street View frames for a route: shows the cached image
 * nearest the current ride distance and crossfades as the rider advances. Fully
 * offline, no flicker — the whole point of prefetching.
 */
@Composable
fun CachedStreetView(
    manifest: StreetViewManifest,
    distanceMeters: Double,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Resolve the nearest cached frame; recomputed only when the file changes.
    val path = remember(manifest, bucket(distanceMeters)) {
        StreetViewCache.imageFileAt(context, manifest, distanceMeters)?.path
    }

    Box(modifier.background(Color.Black)) {
        Crossfade(targetState = path, animationSpec = tween(450), label = "streetview-cache") { p ->
            val bitmap by produceState<ImageBitmap?>(initialValue = null, p) {
                value = p?.let {
                    withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull()
                    }
                }
            }
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "Street View",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (path == null) {
            Text(
                "No prefetched Street View here",
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Bucket the distance so we only re-pick a frame every ~2 m, not every tick. */
private fun bucket(distanceMeters: Double): Long = (distanceMeters / 2.0).toLong()
