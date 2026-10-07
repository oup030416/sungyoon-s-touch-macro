package com.sungyoon.helper.overlay.set

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.sungyoon.helper.R
import kotlin.math.ceil
import kotlin.math.min

data class ReservationProgressState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val rest: Boolean = false,
    val cycleCurrent: Int = 1,
    val cycleTotal: Int = 0,
    val remainingMs: Long = 0,
    val phaseDurationMs: Long = 0
)

/** A presentation-only view; callers supply standalone or item-scoped runtime. */
class ReservationProgressView(context: Context) : FrameLayout(context) {
    private val ring = ProgressRing(context)
    private val status = context.setText(context.getString(R.string.reservation_waiting), 16f, true).apply {
        gravity = Gravity.CENTER
    }
    private val cycles = context.setText("", 11f).apply { gravity = Gravity.CENTER }
    private val detail = context.setText("", 10.5f).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#B8B8B8"))
        setPadding(context.setDp(8), context.setDp(6), context.setDp(8), 0)
    }

    init {
        addView(ring, LayoutParams(context.setDp(168), context.setDp(168), Gravity.CENTER))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(status)
            addView(cycles)
            addView(detail)
        }, LayoutParams(context.setDp(156), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        render(ReservationProgressState())
    }

    fun render(state: ReservationProgressState) {
        val active = state.active && state.cycleTotal > 0
        cycles.visibility = if (active) VISIBLE else GONE
        detail.visibility = if (active) VISIBLE else GONE
        status.text = when {
            !active -> context.getString(R.string.reservation_waiting)
            state.rest && state.paused -> context.getString(R.string.reservation_phase_rest_paused)
            state.paused -> context.getString(R.string.reservation_phase_run_paused)
            state.rest -> context.getString(R.string.reservation_phase_resting)
            else -> context.getString(R.string.reservation_phase_running)
        }
        val cycle = state.cycleCurrent.coerceIn(1, state.cycleTotal.coerceAtLeast(1))
        cycles.text = context.getString(R.string.reservation_cycles, cycle, state.cycleTotal)
        val nextPhase = context.getString(if (state.rest) R.string.reservation_phase_run else R.string.reservation_phase_rest)
        detail.text = context.getString(R.string.reservation_next_phase, nextPhase,
            ceil(state.remainingMs.coerceAtLeast(0) / 1000.0).toInt())
        val progress = if (state.phaseDurationMs > 0) {
            (1f - state.remainingMs.toFloat() / state.phaseDurationMs).coerceIn(0f, 1f)
        } else 0f
        ring.render(active, cycle, state.cycleTotal, state.rest, progress)
    }

    private class ProgressRing(context: Context) : View(context) {
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = context.setDp(14).toFloat()
        }
        private var active = false
        private var cycle = 1
        private var total = 0
        private var rest = false
        private var progress = 0f
        private val gray = Color.parseColor("#CFCFCF")
        private val blue = Color.parseColor("#2F80ED")
        private val purple = Color.parseColor("#9B51E0")

        fun render(active: Boolean, cycle: Int, total: Int, rest: Boolean, progress: Float) {
            if (this.active == active && this.cycle == cycle && this.total == total &&
                this.rest == rest && kotlin.math.abs(this.progress - progress) < 0.002f) return
            this.active = active
            this.cycle = cycle
            this.total = total
            this.rest = rest
            this.progress = progress
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desired = context.setDp(168)
            val size = min(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
            setMeasuredDimension(size, size)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = paint.strokeWidth / 2 + context.setDp(2)
            rect.set(pad, pad, width - pad, height - pad)
            paint.color = gray
            paint.alpha = if (active) 180 else 110
            canvas.drawArc(rect, -90f, 360f, false, paint)
            if (!active || total <= 0) return
            val segments = total * 2
            val current = ((cycle - 1) * 2 + if (rest) 1 else 0).coerceIn(0, segments - 1)
            if (segments > 240) {
                paint.color = if (rest) purple else blue
                paint.alpha = 255
                canvas.drawArc(rect, -90f, 360f * (current + progress) / segments, false, paint)
                return
            }
            val sweep = 360f / segments
            val gap = min(1.4f, sweep / 4)
            val drawSweep = sweep - gap
            for (index in 0..current) {
                val fraction = if (index < current) 1f else progress
                if (fraction <= 0f) continue
                paint.alpha = 255
                paint.color = if (index % 2 == 0) blue else purple
                canvas.drawArc(rect, -90f + index * sweep + gap / 2, drawSweep * fraction, false, paint)
            }
        }
    }
}
