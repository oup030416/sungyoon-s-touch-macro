package com.sungyoon.helper.overlay.floating

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

class TrashDropView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33FF5A5A") // 연붉은 반투명
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#AAFF5A5A")
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = dp(28f)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFFFFF")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = dp(12f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val r = (min(width, height) / 2f) * 0.92f

        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        val icon = "🗑"
        val fmI = iconPaint.fontMetrics
        val iconY = cy - (fmI.ascent + fmI.descent) / 2f - dp(6f)
        canvas.drawText(icon, cx, iconY, iconPaint)

        val hint = "여기에 놓으면 숨김"
        val fmH = hintPaint.fontMetrics
        val hintY = cy + dp(18f) - (fmH.ascent + fmH.descent) / 2f
        canvas.drawText(hint, cx, hintY, hintPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
