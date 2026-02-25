package com.sungyoon.helper.service.highlight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs

class SequenceOverlayView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x553F51B5
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = 0xAA3F51B5.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 24f
    }

    private var label: String = ""
    private var baseRadiusPx: Float = 18f
    private var popScale: Float = 1f

    fun setLabel(text: String) {
        if (label != text) {
            label = text
            invalidate()
        }
    }

    fun setBaseRadiusPx(radiusPx: Float) {
        val next = radiusPx.coerceAtLeast(1f)
        if (abs(baseRadiusPx - next) < 0.5f) return
        baseRadiusPx = next
        ringPaint.strokeWidth = (next * 0.18f).coerceAtLeast(4f)
        textPaint.textSize = (next * 1.05f).coerceAtLeast(12f)
        invalidate()
    }

    fun setPopScale(scale: Float) {
        val next = scale.coerceAtLeast(0.5f)
        if (abs(popScale - next) < 0.01f) return
        popScale = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = baseRadiusPx * popScale

        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        val fm = textPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }
}
