package com.bike.trainer.ui.ride

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.bike.trainer.route.StreetViewCache
import com.bike.trainer.route.StreetViewManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Selectable motion styles for playing back prefetched Street View frames. */
enum class SvMotion(val label: String) {
    DISSOLVE("Dissolve"),
    DOLLY("Dolly"),
    PARALLAX("Parallax"),
    MORPH("Morph"),
}

/**
 * Plays prefetched Street View frames with a choice of motion that fakes forward
 * movement without depth data:
 * - DISSOLVE: just cross-fades (new frame layered over the old, no luminance dip).
 * - DOLLY: uniform forward zoom about the image centre.
 * - PARALLAX: ground-plane illusion — the near road (bottom) rushes faster than
 *   the horizon, via a non-uniform mesh warp.
 * - MORPH: radial expansion from the focus-of-expansion (the vanishing point
 *   ahead), which is what forward-motion optical flow looks like.
 *
 * The new frame is always layered ON TOP of the previous one and faded in, so
 * there's never a dark flash. Fully offline.
 */
@Composable
fun CachedStreetView(
    manifest: StreetViewManifest,
    distanceMeters: Double,
    speedKmh: Double,
    mode: SvMotion,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val path = remember(manifest, bucket(distanceMeters)) {
        StreetViewCache.imageFileAt(context, manifest, distanceMeters)?.path
    }
    val decoded by produceState<Bitmap?>(initialValue = null, path) {
        path?.let {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(it) }.getOrNull()
            }
            if (bmp != null) value = bmp
        }
    }

    var front by remember { mutableStateOf<Bitmap?>(null) }
    var back by remember { mutableStateOf<Bitmap?>(null) }
    val fade = remember { Animatable(1f) }       // front opacity 0→1
    val progress = remember { Animatable(0f) }    // front warp 0→1 across the segment

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
        progress.snapTo(0f)
        fade.snapTo(if (first) 1f else 0f)
        val dissolveMs = (segmentMs / 3).coerceIn(180, 600)
        coroutineScope {
            if (!first) launch { fade.animateTo(1f, tween(dissolveMs, easing = LinearEasing)) }
            launch { progress.animateTo(1f, tween(segmentMs, easing = LinearEasing)) }
        }
    }

    val paint = remember { Paint().apply { isFilterBitmap = true; isAntiAlias = true } }

    Canvas(modifier.background(Color.Black).clipToBounds()) {
        val w = size.width
        val h = size.height
        val fadeV = fade.value
        val progV = progress.value
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            back?.let { drawWarped(nc, it, w, h, 1f, mode, 255, paint) }
            front?.let { drawWarped(nc, it, w, h, progV, mode, (fadeV * 255f).toInt().coerceIn(0, 255), paint) }
        }
    }
}

private const val EXPAND = 0.16f
private const val MESH_W = 12
private const val MESH_H = 10

/** Draws [bmp] cover-cropped to (w,h) and warped per [mode]/[p] via a bitmap mesh. */
private fun drawWarped(
    nc: android.graphics.Canvas,
    bmp: Bitmap,
    w: Float,
    h: Float,
    p: Float,
    mode: SvMotion,
    alpha: Int,
    paint: Paint,
) {
    val bw = bmp.width.toFloat()
    val bh = bmp.height.toFloat()
    if (bw <= 0 || bh <= 0) return
    val cover = max(w / bw, h / bh)
    val dw = bw * cover
    val dh = bh * cover
    val ox = (w - dw) / 2f
    val oy = (h - dh) / 2f

    val foeX = 0.5f * w
    val foeY = 0.45f * h          // vanishing point sits a bit above centre

    val verts = FloatArray((MESH_W + 1) * (MESH_H + 1) * 2)
    var k = 0
    for (i in 0..MESH_H) {
        val v = i.toFloat() / MESH_H
        for (j in 0..MESH_W) {
            val u = j.toFloat() / MESH_W
            val bx = ox + u * dw
            val by = oy + v * dh
            val cx: Float
            val cy: Float
            val s: Float
            when (mode) {
                SvMotion.DISSOLVE -> { cx = bx; cy = by; s = 1f }
                SvMotion.DOLLY -> {
                    cx = 0.5f * w; cy = 0.5f * h; s = 1f + EXPAND * p
                }
                SvMotion.MORPH -> {
                    cx = foeX; cy = foeY; s = 1f + EXPAND * p
                }
                SvMotion.PARALLAX -> {
                    cx = foeX; cy = foeY
                    // Ground (below the horizon) expands more than the sky.
                    val below = (((by / h) - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    s = 1f + EXPAND * p * (0.35f + 1.5f * below)
                }
            }
            verts[k++] = cx + (bx - cx) * s
            verts[k++] = cy + (by - cy) * s
        }
    }
    paint.alpha = alpha
    nc.drawBitmapMesh(bmp, MESH_W, MESH_H, verts, 0, null, 0, paint)
}

/** Bucket the distance so we only re-pick a frame every ~2 m, not every tick. */
private fun bucket(distanceMeters: Double): Long = (distanceMeters / 2.0).toLong()
