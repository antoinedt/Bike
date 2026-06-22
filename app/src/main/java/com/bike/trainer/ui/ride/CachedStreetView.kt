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
 * Advanced, user-tunable knobs for the Street View motion (set in Settings →
 * Street View motion). Defaults reproduce the original look.
 *
 * @param strength overall expansion per segment (how hard the scene pushes forward)
 * @param horizon vanishing-point height as a fraction of the frame (0 = top)
 * @param groundRush Parallax only — extra speed of the near road vs the horizon
 */
data class SvMotionParams(
    val strength: Float = 0.16f,
    val horizon: Float = 0.45f,
    val groundRush: Float = 1.5f,
)

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
 * there's never a dark flash. The previous frame is held at the exact warp it had
 * reached when it was superseded — so the swap never jumps, at any speed. Fully
 * offline.
 */
@Composable
fun CachedStreetView(
    manifest: StreetViewManifest,
    distanceMeters: Double,
    speedKmh: Double,
    mode: SvMotion,
    modifier: Modifier = Modifier,
    params: SvMotionParams = SvMotionParams(),
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
    // The warp progress the previous frame had reached when it became the back
    // frame. Holding it there (instead of forcing 1f) keeps the swap jump-free even
    // when frames arrive faster than a segment completes (high speed).
    var backHold by remember { mutableStateOf(1f) }
    val fade = remember { Animatable(1f) }       // front opacity 0→1
    val progress = remember { Animatable(0f) }    // front warp 0→1 across the segment

    // Time to traverse one frame spacing at the current speed. The 200 ms floor lets
    // the morph keep pace at high speed without the animation thrashing.
    val segmentMs = run {
        val speedMs = (speedKmh / 3.6).coerceAtLeast(0.3)
        (manifest.spacingMeters / speedMs * 1000.0).toInt().coerceIn(200, 6000)
    }

    LaunchedEffect(decoded) {
        val bmp = decoded ?: return@LaunchedEffect
        if (bmp === front) return@LaunchedEffect
        val first = front == null
        if (!first) {
            back = front
            backHold = progress.value   // freeze the outgoing frame where it stopped
        }
        front = bmp
        progress.snapTo(0f)
        fade.snapTo(if (first) 1f else 0f)
        val dissolveMs = (segmentMs / 3).coerceIn(120, 600)
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
            // Back is held at the warp it had reached when it was superseded, so it
            // doesn't snap forward when the next frame takes over — that snap was the
            // "jump", and at high speed it would happen on every swap.
            back?.let { drawWarped(nc, it, w, h, backHold, mode, 255, paint, params) }
            front?.let {
                drawWarped(nc, it, w, h, progV, mode, (fadeV * 255f).toInt().coerceIn(0, 255), paint, params)
            }
        }
    }
}

private const val MESH_W = 12
private const val MESH_H = 10

/**
 * Draws [bmp] cover-cropped to (w,h) and warped via a bitmap mesh, expanding about
 * a centre by [p], scaled by [params]. DISSOLVE applies no expansion (pure fade).
 */
private fun drawWarped(
    nc: android.graphics.Canvas,
    bmp: Bitmap,
    w: Float,
    h: Float,
    p: Float,
    mode: SvMotion,
    alpha: Int,
    paint: Paint,
    params: SvMotionParams,
) {
    val bw = bmp.width.toFloat()
    val bh = bmp.height.toFloat()
    if (bw <= 0 || bh <= 0) return
    val cover = max(w / bw, h / bh)
    val dw = bw * cover
    val dh = bh * cover
    val ox = (w - dw) / 2f
    val oy = (h - dh) / 2f

    val expandStrength = params.strength
    val horizon = params.horizon
    val foeX = 0.5f * w
    val foeY = horizon * h          // vanishing point height (tunable)
    val expand: SvMotion? = if (mode == SvMotion.DISSOLVE) null else mode

    val verts = FloatArray((MESH_W + 1) * (MESH_H + 1) * 2)
    var k = 0
    for (i in 0..MESH_H) {
        val v = i.toFloat() / MESH_H
        for (j in 0..MESH_W) {
            val u = j.toFloat() / MESH_W
            val bx = ox + u * dw
            val by = oy + v * dh
            var x = bx
            var y = by
            if (expand != null) {
                val cx: Float
                val cy: Float
                val s: Float
                when (expand) {
                    SvMotion.DOLLY -> { cx = 0.5f * w; cy = 0.5f * h; s = 1f + expandStrength * p }
                    SvMotion.PARALLAX -> {
                        cx = foeX; cy = foeY
                        // Below-horizon pixels rush faster; groundRush tunes how much.
                        val span = (1f - horizon).coerceAtLeast(0.05f)
                        val below = (((by / h) - horizon) / span).coerceIn(0f, 1f)
                        s = 1f + expandStrength * p * (0.35f + params.groundRush * below)
                    }
                    else -> { cx = foeX; cy = foeY; s = 1f + expandStrength * p } // MORPH
                }
                x = cx + (x - cx) * s
                y = cy + (y - cy) * s
            }
            verts[k++] = x
            verts[k++] = y
        }
    }
    paint.alpha = alpha
    nc.drawBitmapMesh(bmp, MESH_W, MESH_H, verts, 0, null, 0, paint)
}

/** Bucket the distance so we only re-pick a frame every ~2 m, not every tick. */
private fun bucket(distanceMeters: Double): Long = (distanceMeters / 2.0).toLong()
