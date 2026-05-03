package com.example.watermarkcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max

object WatermarkProcessor {

    fun addWatermark(src: Bitmap, lines: List<String>): Bitmap {
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = max(24f, width * 0.02f)
        val infoTextSize = max(34f, width * 0.038f)
        val lineSpacing = infoTextSize * 1.35f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = infoTextSize
            style = Paint.Style.FILL
            setShadowLayer(8f, 2f, 2f, Color.parseColor("#80000000"))
        }

        val textBlockHeight = lineSpacing * lines.size
        val backgroundTop = height - textBlockHeight - padding * 2

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55000000")
            style = Paint.Style.FILL
        }

        canvas.drawRoundRect(
            padding,
            backgroundTop,
            width - padding,
            height - padding,
            18f,
            18f,
            bgPaint
        )

        var y = backgroundTop + padding + infoTextSize
        for (line in lines) {
            canvas.drawText(line, padding * 1.5f, y, textPaint)
            y += lineSpacing
        }

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66FFFFFF")
            textSize = max(68f, width * 0.1f)
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("现场拍照", width / 2f, height / 2f, centerPaint)

        return bitmap
    }
}
