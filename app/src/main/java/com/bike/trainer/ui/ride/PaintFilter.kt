package com.bike.trainer.ui.ride

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a photo into an illustration: Kuwahara smoothing (flat, paint-like areas
 * with crisp boundaries), a little extra colour, a reduced palette, and dark ink
 * outlines along strong edges. Works on ARGB pixel arrays (no Android types) so it
 * can run on any thread; ~O(pixels) thanks to summed-area tables.
 */
object PaintFilter {
    /** Kuwahara quadrant radius (bigger = broader brush strokes). */
    var radius = 3
    /** Brightness bands (toon shading). */
    var levels = 6
    var saturation = 1.25
    /** Edge strength (luminance gradient) where the ink line starts / is full. */
    var edgeLow = 18.0
    var edgeHigh = 55.0
    var inkStrength = 0.8

    fun apply(px: IntArray, w: Int, h: Int): IntArray {
        val img = kuwahara(px, w, h, radius)
        // Ink comes from the (lightly blurred) original so thin structures survive.
        val edgeLum = blur3(DoubleArray(w * h) { luma(px[it]) }, w, h)
        // Band boundaries follow a smoothed brightness so they stay clean curves.
        val bandLum = blur3(blur3(DoubleArray(w * h) { luma(img[it]) }, w, h), w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val c = img[i]
            val r0 = ((c shr 16) and 0xFF).toDouble()
            val g0 = ((c shr 8) and 0xFF).toDouble()
            val b0 = (c and 0xFF).toDouble()
            val l = 0.299 * r0 + 0.587 * g0 + 0.114 * b0
            // Toon shading: quantise brightness into soft bands, keep the real hue.
            val lb = bandLum[i]
            val lq = bands(lb) + (l - lb) * 0.35
            val k0 = if (l > 1.0) lq / l else 1.0
            var r = l + (r0 - l) * saturation
            var g = l + (g0 - l) * saturation
            var b = l + (b0 - l) * saturation
            r *= k0; g *= k0; b *= k0
            val ink = if (x in 1 until w - 1 && y in 1 until h - 1) {
                val e = edgeLum
                val gx = -e[i - w - 1] - 2 * e[i - 1] - e[i + w - 1] + e[i - w + 1] + 2 * e[i + 1] + e[i + w + 1]
                val gy = -e[i - w - 1] - 2 * e[i - w] - e[i - w + 1] + e[i + w - 1] + 2 * e[i + w] + e[i + w + 1]
                val m = sqrt(gx * gx + gy * gy) / 4.0
                smooth((m - edgeLow) / (edgeHigh - edgeLow)) * inkStrength
            } else 0.0
            val k = 1.0 - ink
            out[i] = (0xFF shl 24) or (ch(r * k) shl 16) or (ch(g * k) shl 8) or ch(b * k)
        }
        return out
    }

    /** Soft luminance bands: flat steps with a short ramp between them. */
    private fun bands(l: Double): Double {
        val step = 255.0 / levels
        val f = l / step
        val lo = kotlin.math.floor(f)
        val t = smooth(((f - lo) - 0.7) / 0.3)
        return ((lo + t) * step + step * 0.5).coerceIn(0.0, 255.0)
    }

    private fun ch(v: Double) = v.roundToInt().coerceIn(0, 255)

    private fun blur3(src: DoubleArray, w: Int, h: Int): DoubleArray {
        val out = src.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            out[i] = (src[i - w - 1] + src[i - w] + src[i - w + 1] + src[i - 1] + src[i] + src[i + 1] +
                src[i + w - 1] + src[i + w] + src[i + w + 1]) / 9.0
        }
        return out
    }

    private fun smooth(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    private fun luma(c: Int): Double =
        0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)

    /**
     * Kuwahara filter: each pixel takes the mean colour of whichever of its four
     * overlapping (r+1)² quadrants has the lowest luminance variance.
     */
    private fun kuwahara(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val sw = w + 1
        val sR = DoubleArray(sw * (h + 1))
        val sG = DoubleArray(sw * (h + 1))
        val sB = DoubleArray(sw * (h + 1))
        val sL = DoubleArray(sw * (h + 1))
        val sL2 = DoubleArray(sw * (h + 1))
        for (y in 0 until h) {
            var rr = 0.0; var gg = 0.0; var bb = 0.0; var ll = 0.0; var l2 = 0.0
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r0 = ((c shr 16) and 0xFF).toDouble()
                val g0 = ((c shr 8) and 0xFF).toDouble()
                val b0 = (c and 0xFF).toDouble()
                val l = 0.299 * r0 + 0.587 * g0 + 0.114 * b0
                rr += r0; gg += g0; bb += b0; ll += l; l2 += l * l
                val i = (y + 1) * sw + (x + 1)
                val up = y * sw + (x + 1)
                sR[i] = sR[up] + rr; sG[i] = sG[up] + gg; sB[i] = sB[up] + bb
                sL[i] = sL[up] + ll; sL2[i] = sL2[up] + l2
            }
        }
        fun box(s: DoubleArray, x0: Int, y0: Int, x1: Int, y1: Int): Double =
            s[(y1 + 1) * sw + (x1 + 1)] - s[y0 * sw + (x1 + 1)] - s[(y1 + 1) * sw + x0] + s[y0 * sw + x0]

        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var bestVar = Double.MAX_VALUE
            var br = 0.0; var bg = 0.0; var bbl = 0.0
            for (q in 0..3) {
                val x0 = max(0, if (q and 1 == 0) x - r else x)
                val x1 = min(w - 1, if (q and 1 == 0) x else x + r)
                val y0 = max(0, if (q and 2 == 0) y - r else y)
                val y1 = min(h - 1, if (q and 2 == 0) y else y + r)
                val n = ((x1 - x0 + 1) * (y1 - y0 + 1)).toDouble()
                val mean = box(sL, x0, y0, x1, y1) / n
                val v = box(sL2, x0, y0, x1, y1) / n - mean * mean
                if (v < bestVar) {
                    bestVar = v
                    br = box(sR, x0, y0, x1, y1) / n
                    bg = box(sG, x0, y0, x1, y1) / n
                    bbl = box(sB, x0, y0, x1, y1) / n
                }
            }
            out[y * w + x] = (0xFF shl 24) or (br.roundToInt().coerceIn(0, 255) shl 16) or
                (bg.roundToInt().coerceIn(0, 255) shl 8) or bbl.roundToInt().coerceIn(0, 255)
        }
        return out
    }
}
