package com.sungyoon.helper.overlay.floating

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.annotation.DrawableRes
import com.sungyoon.helper.R
import kotlin.math.min

class FloatingToggleView(
    context: Context,
    private val stateProvider: () -> Boolean // true: pointer overlay showing
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC121212")
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#445B5CE6")
    }

    private val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imgMatrix = Matrix()
    private val clipPath = Path()

    private var bmp: Bitmap? = null

    /** true = 원형을 꽉 채우도록(cover), false = 전체가 보이도록(contain) */
    var coverCenter: Boolean = true
        set(value) {
            field = value
            updateImageMatrix()
            invalidate()
        }

    init {
        // 기본 이미지(원하면 교체)
        setImageRes(R.drawable.ic_macro) // res/drawable/ic_macro.png
    }

    fun setImageRes(@DrawableRes resId: Int) {
        val d = context.getDrawable(resId)
        if (d == null) {
            bmp = null
            invalidate()
            return
        }

        bmp = when (d) {
            is BitmapDrawable -> d.bitmap
            else -> {
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 256
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 256
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                d.setBounds(0, 0, w, h)
                d.draw(c)
                b
            }
        }

        updateClipPath()
        updateImageMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClipPath()
        updateImageMatrix()
    }

    private fun updateClipPath() {
        clipPath.reset()
        val cx = width / 2f
        val cy = height / 2f
        val r = (min(width, height) / 2f) * 0.92f
        clipPath.addCircle(cx, cy, r, Path.Direction.CW)
    }

    private fun updateImageMatrix() {
        val b = bmp ?: return
        if (width <= 0 || height <= 0) return

        val cx = width / 2f
        val cy = height / 2f
        val r = (min(width, height) / 2f) * 0.92f
        val dst = r * 2f

        val bw = b.width.toFloat()
        val bh = b.height.toFloat()

        val scale = if (coverCenter) {
            maxOf(dst / bw, dst / bh) // cover
        } else {
            minOf(dst / bw, dst / bh) // contain
        }

        val scaledW = bw * scale
        val scaledH = bh * scale

        val left = cx - scaledW / 2f
        val top = cy - scaledH / 2f

        imgMatrix.reset()
        imgMatrix.postScale(scale, scale)
        imgMatrix.postTranslate(left, top)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = (min(width, height) / 2f) * 0.92f

        // 상태에 따라 링 강조
        ringPaint.color =
            if (stateProvider()) Color.parseColor("#AA5B5CE6")
            else Color.parseColor("#445B5CE6")

        // 배경 + 링
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        // ✅ 이미지 원형 클립해서 그리기
        val b = bmp
        if (b != null) {
            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(b, imgMatrix, imgPaint)
            canvas.restoreToCount(save)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
