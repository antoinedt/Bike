package com.bike.trainer.ui.world

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.bike.trainer.world.SegFrame
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Street scene segmentation of the prefetched Street View frames, on the phone:
 * DeepLabV3 / MobileNetV2 trained on Cityscapes (Apache 2.0, TensorFlow models),
 * cut to a 513 x 385 input and a 65 x 49 grid of the 19 Cityscapes classes.
 *
 * Each frame's result (class + mean photo colour per cell) is cached next to the
 * world, so an interrupted build picks up where it stopped.
 */
class StreetSegmenter(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(4 * IN_W * IN_H * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(SegFrame.H) { Array(SegFrame.W) { FloatArray(CLASSES) } } }
    private val pixels = IntArray(IN_W * IN_H)

    init {
        val fd = context.assets.openFd(MODEL)
        val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    }

    /** Labels and cell colours for one photo, from [cacheDir] when already done. */
    fun segment(photo: File, cacheDir: File): Pair<ByteArray, IntArray>? {
        val n = SegFrame.W * SegFrame.H
        val cache = File(cacheDir, photo.nameWithoutExtension + ".seg$VERSION")
        if (cache.length() == (n * 4).toLong()) {
            val b = cache.readBytes()
            val labels = b.copyOfRange(0, n)
            val colours = IntArray(n) { i ->
                ((b[n + 3 * i].toInt() and 0xFF) shl 16) or ((b[n + 3 * i + 1].toInt() and 0xFF) shl 8) or (b[n + 3 * i + 2].toInt() and 0xFF)
            }
            return labels to colours
        }
        val bmp = BitmapFactory.decodeFile(photo.absolutePath) ?: return null
        try {
            val scaled = Bitmap.createScaledBitmap(bmp, IN_W, IN_H, true)
            scaled.getPixels(pixels, 0, IN_W, 0, 0, IN_W, IN_H)
            if (scaled !== bmp) scaled.recycle()
            input.rewind()
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF) * (2f / 255f) - 1f)
                input.putFloat(((p shr 8) and 0xFF) * (2f / 255f) - 1f)
                input.putFloat((p and 0xFF) * (2f / 255f) - 1f)
            }
            input.rewind()
            interpreter.run(input, output)
            val labels = ByteArray(n)
            for (r in 0 until SegFrame.H) for (c in 0 until SegFrame.W) {
                val l = output[0][r][c]
                var best = 0
                for (k in 1 until CLASSES) if (l[k] > l[best]) best = k
                labels[r * SegFrame.W + c] = best.toByte()
            }
            val colours = cellColours(bmp)
            val out = ByteArray(n * 4)
            System.arraycopy(labels, 0, out, 0, n)
            for (i in 0 until n) {
                out[n + 3 * i] = (colours[i] shr 16).toByte(); out[n + 3 * i + 1] = (colours[i] shr 8).toByte(); out[n + 3 * i + 2] = colours[i].toByte()
            }
            cacheDir.mkdirs()
            File(cacheDir, cache.name + ".tmp").apply { writeBytes(out); renameTo(cache) }
            return labels to colours
        } finally {
            bmp.recycle()
        }
    }

    /** Mean photo colour in each grid cell. */
    private fun cellColours(bmp: Bitmap): IntArray {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        val sum = LongArray(SegFrame.W * SegFrame.H * 4)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val r = (y * SegFrame.H / h).coerceAtMost(SegFrame.H - 1)
            for (x in 0 until w) {
                val c = (x * SegFrame.W / w).coerceAtMost(SegFrame.W - 1)
                val k = 4 * (r * SegFrame.W + c)
                val p = row[x]
                sum[k] += ((p shr 16) and 0xFF).toLong(); sum[k + 1] += ((p shr 8) and 0xFF).toLong(); sum[k + 2] += (p and 0xFF).toLong(); sum[k + 3]++
            }
        }
        return IntArray(SegFrame.W * SegFrame.H) { i ->
            val cnt = sum[4 * i + 3].coerceAtLeast(1)
            ((sum[4 * i] / cnt).toInt() shl 16) or ((sum[4 * i + 1] / cnt).toInt() shl 8) or (sum[4 * i + 2] / cnt).toInt()
        }
    }

    override fun close() = interpreter.close()

    companion object {
        private const val MODEL = "models/streetseg.tflite"
        private const val VERSION = 1
        private const val IN_W = 513
        private const val IN_H = 385
        private const val CLASSES = 19
    }
}
