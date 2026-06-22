package com.bike.trainer.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Builds a shareable recap image — the in-ride screenshot with a stats overlay
 * baked in — and saves it to the device gallery/files.
 */
object RecapImage {

    /** Draw [title] + [stats] (label → value) onto a copy of [base]. */
    fun compose(base: Bitmap, title: String, stats: List<Pair<String, String>>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val barH = h * 0.32f
        val pad = w * 0.045f

        val gradient = Paint().apply {
            shader = LinearGradient(
                0f, h - barH, 0f, h,
                Color.TRANSPARENT, Color.argb(225, 8, 12, 16), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, h - barH, w, h, gradient)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = w * 0.052f
            isFakeBoldText = true
        }
        c.drawText(ellipsize(title, titlePaint, w - 2 * pad), pad, h - barH + titlePaint.textSize * 1.2f, titlePaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(205, 180, 190, 200)
            textSize = w * 0.026f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = w * 0.05f
            isFakeBoldText = true
        }
        val n = stats.size.coerceAtLeast(1)
        val colW = (w - 2 * pad) / n
        val valueY = h - pad
        val labelY = valueY - valuePaint.textSize - labelPaint.textSize * 0.5f
        stats.forEachIndexed { i, (label, value) ->
            val x = pad + i * colW
            c.drawText(label.uppercase(), x, labelY, labelPaint)
            c.drawText(value, x, valueY, valuePaint)
        }

        val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 122, 26)
            textSize = w * 0.038f
            isFakeBoldText = true
        }
        c.drawText("BIKE", w - pad - watermark.measureText("BIKE"), h - barH + watermark.textSize * 1.2f, watermark)
        return out
    }

    /** Save [bitmap] as a JPEG; returns a user-facing location, or null on failure. */
    fun save(context: Context, bitmap: Bitmap, displayName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Bike")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            "Pictures/Bike/$displayName.jpg"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Bike")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$displayName.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            file.absolutePath
        }
    }.getOrNull()

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}
