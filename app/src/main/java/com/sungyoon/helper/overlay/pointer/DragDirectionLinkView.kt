package com.sungyoon.helper.overlay.pointer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.min

class DragDirectionLinkView(
    context: Context,
    lineThicknessPx: Float,
    private val arrowLengthPx: Float,
    private val arrowHalfWidthPx: Float,
    color: Int
) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = lineThicknessPx
        this.color = color
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private val arrowPath = Path()
    private var startInsetPx = 0f
    private var endInsetPx = 0f

    fun setInsets(startInsetPx: Float, endInsetPx: Float) {
        val nextStart = startInsetPx.coerceAtLeast(0f)
        val nextEnd = endInsetPx.coerceAtLeast(0f)
        if (abs(this.startInsetPx - nextStart) < 0.5f && abs(this.endInsetPx - nextEnd) < 0.5f) {
            return
        }
        this.startInsetPx = nextStart
        this.endInsetPx = nextEnd
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val cy = h / 2f
        val startX = startInsetPx.coerceIn(0f, w)
        val endX = (w - endInsetPx).coerceIn(0f, w)
        val available = endX - startX
        if (available <= 1f) return

        val arrowLen = min(arrowLengthPx, available * 0.45f).coerceAtLeast(1f)
        val shaftEndX = (endX - arrowLen).coerceAtLeast(startX)
        if (shaftEndX > startX + 0.5f) {
            canvas.drawLine(startX, cy, shaftEndX, cy, linePaint)
        }

        val headHalfWidth = min(arrowHalfWidthPx, arrowLen * 0.75f).coerceAtLeast(1f)
        arrowPath.reset()
        arrowPath.moveTo(endX, cy)
        arrowPath.lineTo(shaftEndX, cy - headHalfWidth)
        arrowPath.lineTo(shaftEndX, cy + headHalfWidth)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }
}
