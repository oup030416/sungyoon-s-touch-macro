package com.sungyoon.helper.overlay.set

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/** Finite service messages remain independent of ordinary overlay permissions and run progress. */
class SetMessageOverlayController(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var messageView: TextView? = null
    private var disposed = false
    private val dismissMessage = Runnable { removeMessage() }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    fun showMessage(message: String) {
        if (disposed) return
        handler.removeCallbacks(dismissMessage)
        removeMessage()
        val screen = screenBounds()
        val text = TextView(context).apply {
            this.text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxWidth = (screen.width() - dp(32)).coerceAtLeast(1)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E6111111"))
            }
        }
        val messageLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = minOf(dp(110), (screen.height() / 3).coerceAtLeast(0))
        }
        try {
            wm.addView(text, messageLp)
            messageView = text
            handler.postDelayed(dismissMessage, 1_600L)
        } catch (_: RuntimeException) { }
    }

    private fun removeMessage() {
        messageView?.let { try { wm.removeView(it) } catch (_: RuntimeException) { } }
        messageView = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        handler.removeCallbacks(dismissMessage)
        removeMessage()
    }

    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wm.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
}
