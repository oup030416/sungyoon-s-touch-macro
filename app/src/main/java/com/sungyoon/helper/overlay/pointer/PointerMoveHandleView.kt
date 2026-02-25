package com.sungyoon.helper.overlay.pointer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class PointerMoveHandleView(
    context: Context,
    private val dp: (Int) -> Int
) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3).toFloat()
        color = Color.parseColor("#E6FFFFFF")
        alpha = 210
    }

    private val knobFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC121212")
    }

    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        color = Color.parseColor("#AA5B5CE6")
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2).toFloat()
        color = Color.WHITE
        alpha = 235
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var selectedId: String? = null

    private var pointerCx = 0f
    private var pointerCy = 0f

    private var pointerSizePx: Int = dp(44)
    private var defaultLineLenPx: Int = dp(120)
    private var minLineLenPx: Int = dp(44)

    private var knobRadiusPx: Float = (dp(22)).toFloat()

    private var dragging = false
    private var moved = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startCx = 0f
    private var startCy = 0f

    private var onDragStart: ((String) -> Unit)? = null
    private var onDragMove: ((String, Float, Float) -> Unit)? = null
    private var onDragEnd: ((String, Float, Float) -> Unit)? = null

    fun setCallbacks(
        onDragStart: (String) -> Unit,
        onDragMove: (String, Float, Float) -> Unit,
        onDragEnd: (String, Float, Float) -> Unit
    ) {
        this.onDragStart = onDragStart
        this.onDragMove = onDragMove
        this.onDragEnd = onDragEnd
    }

    fun showFor(
        id: String,
        cx: Float,
        cy: Float,
        pointerSizePx: Int,
        parentW: Int,
        parentH: Int
    ) {
        this.selectedId = id
        this.pointerCx = cx
        this.pointerCy = cy
        this.pointerSizePx = pointerSizePx

        // 라인 길이: 아래 공간에 맞춰 가변(항상 아래로)
        val margin = dp(12)
        val knobD = (knobRadiusPx * 2f).roundToInt()
        val maxLen = (parentH - cy - margin - knobD).roundToInt()
        val lineLen = maxLen.coerceIn(0, defaultLineLenPx).coerceAtLeast(minLineLenPx)

        val w = maxOf(knobD, dp(44))
        val h = lineLen + knobD

        layoutParams = (layoutParams ?: android.widget.FrameLayout.LayoutParams(w, h)).also {
            it.width = w
            it.height = h
        }

        // 위치: 핸들의 상단 중앙이 포인터 중심에 오도록
        val left = (cx - w / 2f).roundToInt()
        val top = (cy).roundToInt()
        val clampedLeft = left.coerceIn(margin, (parentW - w - margin).coerceAtLeast(margin))
        val clampedTop = top.coerceIn(margin, (parentH - h - margin).coerceAtLeast(margin))

        x = clampedLeft.toFloat()
        y = clampedTop.toFloat()

        visibility = VISIBLE
        alpha = 0f
        animate().cancel()
        animate().alpha(1f).setDuration(120L).start()

        invalidate()
    }

    fun hide() {
        selectedId = null
        dragging = false
        moved = false
        animate().cancel()
        visibility = GONE
    }

    fun updateAnchor(
        cx: Float,
        cy: Float,
        parentW: Int,
        parentH: Int
    ) {
        val id = selectedId ?: return
        showFor(id, cx, cy, pointerSizePx, parentW, parentH)
    }

    private fun knobCenterLocal(): Pair<Float, Float> {
        val cx = width / 2f
        val cy = (height - knobRadiusPx)
        return cx to cy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE) return

        val (kcX, kcY) = knobCenterLocal()
        val topX = width / 2f
        val topY = 0f

        // 라인
        canvas.drawLine(topX, topY + dp(2).toFloat(), kcX, kcY - knobRadiusPx, linePaint)

        // 노브(둥근 버튼)
        canvas.drawCircle(kcX, kcY, knobRadiusPx, knobFillPaint)
        canvas.drawCircle(kcX, kcY, knobRadiusPx, knobRingPaint)

        // 이동 아이콘(간단한 4방향)
        drawMoveIcon(canvas, kcX, kcY, knobRadiusPx * 0.55f)
    }

    private fun drawMoveIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val s = size.coerceAtLeast(dp(8).toFloat())
        val path = Path()

        // vertical line
        path.moveTo(cx, cy - s)
        path.lineTo(cx, cy + s)
        // horizontal line
        path.moveTo(cx - s, cy)
        path.lineTo(cx + s, cy)

        // arrow heads
        val ah = (s * 0.45f).coerceAtLeast(dp(4).toFloat())

        // up
        path.moveTo(cx, cy - s)
        path.lineTo(cx - ah, cy - s + ah)
        path.moveTo(cx, cy - s)
        path.lineTo(cx + ah, cy - s + ah)

        // down
        path.moveTo(cx, cy + s)
        path.lineTo(cx - ah, cy + s - ah)
        path.moveTo(cx, cy + s)
        path.lineTo(cx + ah, cy + s - ah)

        // left
        path.moveTo(cx - s, cy)
        path.lineTo(cx - s + ah, cy - ah)
        path.moveTo(cx - s, cy)
        path.lineTo(cx - s + ah, cy + ah)

        // right
        path.moveTo(cx + s, cy)
        path.lineTo(cx + s - ah, cy - ah)
        path.moveTo(cx + s, cy)
        path.lineTo(cx + s - ah, cy + ah)

        canvas.drawPath(path, iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val id = selectedId ?: return false

        val (kcX, kcY) = knobCenterLocal()
        val localX = event.x
        val localY = event.y

        fun isInsideKnob(x: Float, y: Float): Boolean {
            val d = hypot(x - kcX, y - kcY)
            return d <= (knobRadiusPx * 1.15f)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInsideKnob(localX, localY)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                moved = false
                downRawX = event.rawX
                downRawY = event.rawY
                startCx = pointerCx
                startCy = pointerCy
                onDragStart?.invoke(id)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true

                val parentView = parent as? View ?: return true
                val newCx = (startCx + dx).coerceIn(pointerSizePx / 2f, (parentView.width - pointerSizePx / 2f).coerceAtLeast(pointerSizePx / 2f))
                val newCy = (startCy + dy).coerceIn(pointerSizePx / 2f, (parentView.height - pointerSizePx / 2f).coerceAtLeast(pointerSizePx / 2f))

                pointerCx = newCx
                pointerCy = newCy
                onDragMove?.invoke(id, newCx, newCy)
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                onDragEnd?.invoke(id, pointerCx, pointerCy)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
