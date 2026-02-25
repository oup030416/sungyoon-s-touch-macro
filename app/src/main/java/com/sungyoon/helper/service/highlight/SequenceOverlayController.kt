package com.sungyoon.helper.service.highlight

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.delay

class SequenceOverlayController(
    private val service: AccessibilityService,
    private val tag: String
) {
    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private var view: SequenceOverlayView? = null
    private var added = false
    private var popAnimator: ValueAnimator? = null
    private var pointerRadiusPx: Float = 18f * service.resources.displayMetrics.density

    private fun overlayType(): Int {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(service)
        } else {
            true
        }

        return if (canDraw) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
    }

    private val sizePx: Int by lazy {
        val density = service.resources.displayMetrics.density
        (120f * density).toInt()
    }

    private val lp: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    fun show() {
        if (added) return
        val v = SequenceOverlayView(service).apply {
            setBaseRadiusPx(pointerRadiusPx)
            setPopScale(1f)
        }
        view = v
        wm.addView(v, lp)
        added = true
    }

    fun hide() {
        popAnimator?.cancel()
        popAnimator = null
        view?.setPopScale(1f)

        if (!added) return
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Throwable) {
            }
        }
        view = null
        added = false
    }

    fun setPointerRadiusDp(radiusDp: Int) {
        val density = service.resources.displayMetrics.density
        pointerRadiusPx = (radiusDp.coerceAtLeast(1) * density).coerceAtLeast(1f)
        view?.setBaseRadiusPx(pointerRadiusPx)
    }

    fun triggerPop() {
        val v = view ?: return
        popAnimator?.cancel()
        popAnimator = ValueAnimator.ofFloat(1f, 1.25f, 1f).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                v.setPopScale(animator.animatedValue as Float)
            }
            start()
        }
    }

    fun moveTo(x: Float, y: Float, label: String) {
        view?.setLabel(label)
        lp.x = (x - sizePx / 2f).toInt()
        lp.y = (y - sizePx / 2f).toInt()

        view?.let {
            try {
                wm.updateViewLayout(it, lp)
            } catch (t: Throwable) {
                Log.e(tag, "updateViewLayout failed", t)
            }
        }
    }

    suspend fun animateDragRealtime(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        label: String
    ) {
        val duration = durationMs.coerceAtLeast(1L)
        val startAt = SystemClock.uptimeMillis()
        moveTo(fromX, fromY, label)

        while (true) {
            val elapsed = (SystemClock.uptimeMillis() - startAt).coerceAtLeast(0L)
            val t = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val x = fromX + (toX - fromX) * t
            val y = fromY + (toY - fromY) * t
            moveTo(x, y, label)
            if (t >= 1f) break
            delay(16L)
        }
    }

    fun dispose() = hide()
}
