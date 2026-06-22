package com.bike.trainer.ui.ride

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Dense optical flow (OpenCV Farnebäck) used by the "Flow" Street View morph.
 * Everything is best-effort and isolated here: if OpenCV can't load, callers get
 * null and fall back to the analytic morph.
 */
object OpticalFlow {

    @Volatile
    private var ready = false

    private fun ensure(): Boolean {
        if (ready) return true
        ready = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        return ready
    }

    /**
     * Flow from [a] to [b] sampled onto a (cols+1)×(rows+1) grid, returned as
     * normalized displacements (fraction of width/height), interleaved dx,dy.
     * Null if OpenCV is unavailable or anything fails.
     */
    fun gridFlow(a: Bitmap, b: Bitmap, cols: Int, rows: Int): FloatArray? {
        if (!ensure()) return null
        return runCatching {
            val sw = 320.0
            val sh = 240.0
            val ga = gray(a, sw, sh)
            val gb = gray(b, sw, sh)
            val flow = Mat()
            Video.calcOpticalFlowFarneback(ga, gb, flow, 0.5, 3, 15, 3, 5, 1.2, 0)
            val out = FloatArray((cols + 1) * (rows + 1) * 2)
            var k = 0
            for (i in 0..rows) {
                val y = ((i.toDouble() / rows) * (sh - 1)).toInt()
                for (j in 0..cols) {
                    val x = ((j.toDouble() / cols) * (sw - 1)).toInt()
                    val f = flow.get(y, x)
                    val dx = f?.getOrNull(0) ?: 0.0
                    val dy = f?.getOrNull(1) ?: 0.0
                    out[k++] = (dx / sw).toFloat()
                    out[k++] = (dy / sh).toFloat()
                }
            }
            ga.release(); gb.release(); flow.release()
            out
        }.getOrNull()
    }

    private fun gray(bmp: Bitmap, w: Double, h: Double): Mat {
        val src = Mat()
        Utils.bitmapToMat(bmp, src)
        val g = Mat()
        Imgproc.cvtColor(src, g, Imgproc.COLOR_RGBA2GRAY)
        val small = Mat()
        Imgproc.resize(g, small, Size(w, h))
        src.release(); g.release()
        return small
    }
}
