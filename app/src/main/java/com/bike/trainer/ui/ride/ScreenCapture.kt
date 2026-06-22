package com.bike.trainer.ui.ride

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Captures a region of the activity window — including the MapLibre / Street View
 * GL surfaces, which a normal view draw can't reach — into a Bitmap via PixelCopy.
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

    suspend fun capture(window: Window, rect: Rect): Bitmap? {
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val dest = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    window,
                    rect,
                    dest,
                    { result ->
                        if (result == PixelCopy.SUCCESS) cont.resume(dest) else cont.resume(null)
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: Throwable) {
                cont.resume(null)
            }
        }
    }
}
