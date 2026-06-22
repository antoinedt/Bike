package com.bike.trainer.ui.ride

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Captures the activity window — including the MapLibre / Street View GL surfaces,
 * which a normal view draw can't reach — into a Bitmap via PixelCopy.
 *
 * We copy the whole window and then crop, rather than asking PixelCopy for a
 * sub-rectangle: the sub-rect variant throws / returns no data on some devices
 * when the requested rect doesn't sit perfectly inside the window surface, which
 * is what made the in-ride capture silently fail over Street View.
 */
object ScreenCapture {

    fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Capture the ride scene. If [sceneView] hosts a GL [SurfaceView] (live Street
     * View / map), copy that surface directly — some devices read GL surfaces back
     * as black through a whole-window copy. Otherwise fall back to the windowed
     * copy cropped to [crop].
     */
    suspend fun captureScene(window: Window, sceneView: View?, crop: Rect?): Bitmap? {
        val sv = findSurfaceView(sceneView)
        if (sv != null && sv.width > 0 && sv.height > 0) {
            val dest = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            val ok = suspendCancellableCoroutine { cont ->
                try {
                    PixelCopy.request(
                        sv,
                        dest,
                        { result -> cont.resume(result == PixelCopy.SUCCESS) },
                        Handler(Looper.getMainLooper()),
                    )
                } catch (_: Throwable) {
                    cont.resume(false)
                }
            }
            if (ok) return dest
        }
        return capture(window, crop)
    }

    private fun findSurfaceView(root: View?): SurfaceView? {
        if (root == null) return null
        if (root is SurfaceView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findSurfaceView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Capture [window]; if [crop] is given, return just that region (clamped). */
    suspend fun capture(window: Window, crop: Rect? = null): Bitmap? {
        val decor = window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return null
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    window,
                    full,
                    { result -> cont.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: Throwable) {
                cont.resume(false)
            }
        }
        if (!ok) return null

        val r = crop?.let { Rect(it).apply { intersect(0, 0, w, h) } }
        return if (r != null && r.width() > 0 && r.height() > 0) {
            Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
        } else {
            full
        }
    }
}
