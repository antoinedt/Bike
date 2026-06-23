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

    /**
     * Draw [stats] (label → value) onto a copy of [base], in a compact 2-row,
     * right-aligned block. The route name is intentionally NOT drawn — the
     * captured frame already shows it in the top HUD. [title] is kept for API
     * compatibility but unused.
     */
    @Suppress("UNUSED_PARAMETER")
    fun compose(base: Bitmap, title: String, stats: List<Pair<String, String>>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val pad = w * 0.045f

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(205, 180, 190, 200)
            textSize = w * 0.024f
            textAlign = Paint.Align.RIGHT
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = w * 0.044f
            isFakeBoldText = true
            textAlign = Paint.Align.RIGHT
        }

        // Two rows of two stats, anchored to the bottom-right.
        val cellH = valuePaint.textSize + labelPaint.textSize * 1.7f
        val colW = w * 0.34f
        val barH = cellH * 2f + pad * 1.6f
        val gradient = Paint().apply {
            shader = LinearGradient(
                0f, h - barH, 0f, h,
                Color.TRANSPARENT, Color.argb(225, 8, 12, 16), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, h - barH, w, h, gradient)

        val rightX = w - pad
        val bottomValueY = h - pad
        stats.take(4).forEachIndexed { i, (label, value) ->
            val col = i % 2          // 0 = left column, 1 = right column
            val row = i / 2          // 0 = top row, 1 = bottom row
            val cellRightX = rightX - (1 - col) * colW
            val valueY = bottomValueY - (1 - row) * cellH
            val labelY = valueY - valuePaint.textSize
            c.drawText(label.uppercase(), cellRightX, labelY, labelPaint)
            c.drawText(value, cellRightX, valueY, valuePaint)
        }

        val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 122, 26)
            textSize = w * 0.038f
            isFakeBoldText = true
        }
        c.drawText("VibeBike", pad, h - pad, watermark)
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
