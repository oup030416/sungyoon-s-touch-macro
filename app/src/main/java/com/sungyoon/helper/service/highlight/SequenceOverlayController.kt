package com.sungyoon.helper.service.highlight

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * ✅ 기존 OverlayController와 동일 기능
 * - WindowManager로 하이라이트 뷰 표시/이동/숨김
 * - canDrawOverlays 여부에 따라 overlay type 선택
 * - pulse 애니메이션 동일(sin 기반, 16ms)
 */
class SequenceOverlayController(
    private val service: AccessibilityService,
    private val tag: String
) {
    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private var view: SequenceOverlayView? = null
    private var added = false

    private var pulseAnimator: ValueAnimator? = null

    private fun overlayType(): Int {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(service)
        } else true

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

        val v = SequenceOverlayView(service)
        view = v

        wm.addView(v, lp)
        added = true

        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.9f, 1.1f).apply {
            duration = 420L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                v.setPulse(animator.animatedValue as Float)
            }
            start()
        }
    }

    fun hide() {
        pulseAnimator?.cancel()
        pulseAnimator = null

        if (!added) return
        view?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        view = null
        added = false
    }

    fun moveTo(x: Float, y: Float, label: String) {
        view?.setLabel(label)

        lp.x = (x - sizePx / 2f).toInt()
        lp.y = (y - sizePx / 2f).toInt()

        view?.let {
            try { wm.updateViewLayout(it, lp) }
            catch (t: Throwable) { Log.e(tag, "updateViewLayout failed", t) }
        }
    }

    fun dispose() = hide()
}
