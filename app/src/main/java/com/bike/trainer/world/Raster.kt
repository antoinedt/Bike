package com.bike.trainer.world

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A tiny software canvas (RGB, row 0 = north edge) covering a square of the world,
 * used to paint ground textures: filled polygons (even-odd, with holes), thick
 * polylines with round joins, and dashed lines. Pure Kotlin so the world builder
 * runs the same on the phone and on a desktop JVM.
 */
class Raster(val size: Int, private val originX: Double, private val originY: Double, private val meters: Double) {
    val px = IntArray(size * size)
    private val scale = size / meters
    private val minWidth = 1.2 / scale

    fun fill(rgb: Int) = px.fill(rgb)

    private fun cx(x: Double) = (x - originX) * scale
    /** Row 0 at the north edge so the image reads like a map. */
    private fun cy(y: Double) = (originY + meters - y) * scale

    /** Fill polygon rings (even-odd, so inner rings become holes). */
    fun polygon(rings: List<DoubleArray>, rgb: Int) {
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (r in rings) { var i = 1; while (i < r.size) { val v = cy(r[i]); minY = min(minY, v); maxY = max(maxY, v); i += 2 } }
        val y0 = max(0, floor(minY).toInt()); val y1 = min(size - 1, ceil(maxY).toInt())
        if (y0 > y1) return
        val xs = DoubleArray(256)
        for (row in y0..y1) {
            val sy = row + 0.5
            var n = 0
            var xsArr = xs
            for (r in rings) {
                val pts = r.size / 2
                for (i in 0 until pts) {
                    val j = (i + 1) % pts
                    val ay = cy(r[2 * i + 1]); val by = cy(r[2 * j + 1])
                    if ((ay <= sy) == (by <= sy)) continue
                    val ax = cx(r[2 * i]); val bx = cx(r[2 * j])
                    if (n >= xsArr.size) xsArr = xsArr.copyOf(xsArr.size * 2)
                    xsArr[n++] = ax + (sy - ay) / (by - ay) * (bx - ax)
                }
            }
            if (n < 2) continue
            java.util.Arrays.sort(xsArr, 0, n)
            var k = 0
            while (k + 1 < n) {
                val a = max(0, ceil(xsArr[k] - 0.5).toInt())
                val b = min(size - 1, floor(xsArr[k + 1] - 0.5).toInt())
                val base = row * size
                for (c in a..b) px[base + c] = rgb
                k += 2
            }
        }
    }

    /** A thick line (width in metres) along interleaved points, with round joins/caps. */
    fun polyline(xy: DoubleArray, width: Double, rgb: Int) {
        // Never thinner than ~1.2 px, or the scanline fill turns a thin line into dots.
        val h = max(width, minWidth) / 2
        val n = xy.size / 2
        for (i in 0 until n - 1) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]; val bx = xy[2 * i + 2]; val by = xy[2 * i + 3]
            val d = hypot(bx - ax, by - ay)
            if (d < 1e-6) continue
            val nx = -(by - ay) / d * h; val ny = (bx - ax) / d * h
            polygon(listOf(doubleArrayOf(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, ax - nx, ay - ny)), rgb)
        }
        for (i in 0 until n) disc(xy[2 * i], xy[2 * i + 1], h, rgb)
    }

    /** Dashed thin line: [on] metres painted, [off] metres gap. */
    fun dashed(xy: DoubleArray, width: Double, on: Double, off: Double, rgb: Int) {
        var phase = 0.0
        val n = xy.size / 2
        for (i in 0 until n - 1) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]; val bx = xy[2 * i + 2]; val by = xy[2 * i + 3]
            val d = hypot(bx - ax, by - ay)
            var s = 0.0
            while (s < d) {
                val period = on + off
                val inPhase = phase % period
                val len = if (inPhase < on) min(on - inPhase, d - s) else min(period - inPhase, d - s)
                if (inPhase < on) {
                    val t0 = s / d; val t1 = (s + len) / d
                    val p = doubleArrayOf(ax + (bx - ax) * t0, ay + (by - ay) * t0, ax + (bx - ax) * t1, ay + (by - ay) * t1)
                    val hw = max(width, minWidth) / 2
                    val nx = -(by - ay) / d * hw; val ny = (bx - ax) / d * hw
                    polygon(listOf(doubleArrayOf(p[0] + nx, p[1] + ny, p[2] + nx, p[3] + ny, p[2] - nx, p[3] - ny, p[0] - nx, p[1] - ny)), rgb)
                }
                s += len; phase += len
            }
        }
    }

    fun disc(x: Double, y: Double, r: Double, rgb: Int) {
        val c0 = cx(x); val r0 = cy(y); val rp = r * scale
        val ya = max(0, floor(r0 - rp).toInt()); val yb = min(size - 1, ceil(r0 + rp).toInt())
        for (row in ya..yb) {
            val dy = row + 0.5 - r0
            val half = rp * rp - dy * dy
            if (half < 0) continue
            val w = kotlin.math.sqrt(half)
            val a = max(0, ceil(c0 - w - 0.5).toInt()); val b = min(size - 1, floor(c0 + w - 0.5).toInt())
            for (c in a..b) px[row * size + c] = rgb
        }
    }

    /** Colour at a world position (for placing props), or null outside. */
    fun at(x: Double, y: Double): Int? {
        val c = cx(x).toInt(); val r = cy(y).toInt()
        if (c !in 0 until size || r !in 0 until size) return null
        return px[r * size + c]
    }

    fun png(): ByteArray = Png.encode(px, size, size)
}

/** Minimal PNG encoder (8-bit RGB, no filtering). */
object Png {
    fun encode(rgb: IntArray, w: Int, h: Int): ByteArray {
        val raw = ByteArray((w * 3 + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0
            for (x in 0 until w) {
                val c = rgb[y * w + x]
                raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte()
            }
        }
        val def = Deflater(6)
        def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!def.finished()) { val k = def.deflate(buf); z.write(buf, 0, k) }
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().apply {
            int32(this, w); int32(this, h); write(8); write(2); write(0); write(0); write(0)
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun int32(o: ByteArrayOutputStream, v: Int) {
        o.write(v ushr 24); o.write(v ushr 16 and 0xFF); o.write(v ushr 8 and 0xFF); o.write(v and 0xFF)
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        int32(out, data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        int32(out, crc.value.toInt())
    }
}
