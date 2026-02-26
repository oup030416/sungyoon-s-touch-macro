package com.sungyoon.helper.overlay.pointer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

class DraggablePointerView(
    context: Context,
    private val sizePx: Int,
    drawRadiusPx: Float
) : View(context) {

    private var drawRadiusPx: Float = drawRadiusPx

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x553F51B5
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xAA3F51B5.toInt()
        strokeWidth = 6f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = (sizePx * 0.42f)
    }

    private var label: String = ""

    init {
        applyRadiusStyle()
    }

    fun setLabel(text: String) {
        if (label != text) {
            label = text
            invalidate()
        }
    }

    fun setCenter(cx: Float, cy: Float) {
        x = cx - sizePx / 2f
        y = cy - sizePx / 2f
    }

    fun getCenterX(): Float = x + sizePx / 2f
    fun getCenterY(): Float = y + sizePx / 2f

    /** ✅ 부드럽게 크기 반영: 재생성 없이 반지름만 변경 */
    fun setDrawRadiusPx(radiusPx: Float) {
        val next = radiusPx.coerceAtLeast(0f)
        if (kotlin.math.abs(drawRadiusPx - next) < 0.5f) return
        drawRadiusPx = next
        applyRadiusStyle()
        invalidate()
    }

    private fun applyRadiusStyle() {
        val r = drawRadiusPx.coerceAtLeast(0f)
        ringPaint.strokeWidth = (r * 0.18f).coerceAtLeast(6f)
        textPaint.textSize = (r * 1.05f).coerceAtLeast(12f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        val maxR = (min(width, height) / 2f) * 0.90f
        val r = drawRadiusPx.coerceAtMost(maxR)

        if (r > 0f) {
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        val fm = textPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }
}
