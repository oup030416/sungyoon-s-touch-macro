package com.sungyoon.helper.util

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

object OverlayToast {

    private val main = Handler(Looper.getMainLooper())

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun canDrawOverlays(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        } catch (_: Throwable) {
            false
        }
    }

    private fun dp(ctx: Context, v: Int): Int {
        val d = ctx.resources.displayMetrics.density
        return (v * d).roundToInt()
    }

    fun show(ctx: Context, msg: String, durationMs: Long = 1600L) {
        // ✅ 권한 없으면 오버레이 토스트 불가 -> 그냥 종료
        if (!canDrawOverlays(ctx)) return

        main.post {
            val wm = (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return@post

            val tv = TextView(ctx).apply {
                text = msg
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
                alpha = 0f
                isClickable = false
                isFocusable = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(ctx, 16).toFloat()
                    setColor(Color.parseColor("#CC111111"))
                    setStroke(dp(ctx, 1), Color.parseColor("#33FFFFFF"))
                }
                elevation = dp(ctx, 6).toFloat()
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(ctx, 110)
            }

            try {
                wm.addView(tv, lp)
            } catch (_: Throwable) {
                return@post
            }

            tv.animate().alpha(1f).setDuration(140L).start()

            main.postDelayed({
                try {
                    tv.animate()
                        .alpha(0f)
                        .setDuration(160L)
                        .withEndAction {
                            try { wm.removeView(tv) } catch (_: Throwable) {}
                        }
                        .start()
                } catch (_: Throwable) {
                    try { wm.removeView(tv) } catch (_: Throwable) {}
                }
            }, durationMs)
        }
    }

}
