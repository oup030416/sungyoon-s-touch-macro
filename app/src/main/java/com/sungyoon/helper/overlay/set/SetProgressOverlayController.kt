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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.sungyoon.helper.service.set.SetRunState
import kotlin.math.max
import kotlin.math.roundToInt

/** Touch-through progress beside the floating manager toggle. */
class SetProgressOverlayController(
    private val context: Context,
    private val anchorBounds: () -> Rect?,
    private val managerVisible: () -> Boolean
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private var latestState = SetRunState()
    private var added = false
    private var disposed = false
    private val handler = Handler(Looper.getMainLooper())
    private var messageView: TextView? = null
    private val dismissMessage = Runnable { removeMessage() }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val detail = TextView(context).apply {
        setTextColor(Color.parseColor("#DDFFFFFF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#E61B2735"))
            setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#445A8397"))
        }
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        elevation = dp(6).toFloat()
    }
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun update(state: SetRunState) {
        if (disposed) return
        latestState = state
        title.text = SetProgressFormatter.title(context, state)
        detail.text = SetProgressFormatter.detail(context, state)
        refreshPosition()
    }

    fun refreshPosition() {
        if (disposed) return
        val anchor = anchorBounds()
        if (!latestState.active || managerVisible() || anchor == null) {
            hide()
            return
        }
        val screen = screenBounds()
        val padding = dp(8)
        val leftSpace = (anchor.left - padding * 2).coerceAtLeast(0)
        val rightSpace = (screen.width() - anchor.right - padding * 2).coerceAtLeast(0)
        val maxWidth = (screen.width() - padding * 2).coerceAtLeast(1)
        val preferredWidth = minOf(dp(210), maxWidth)
        val beside = max(leftSpace, rightSpace) >= minOf(dp(100), preferredWidth)
        val useRight = rightSpace >= leftSpace
        val width = if (beside) minOf(preferredWidth, if (useRight) rightSpace else leftSpace) else preferredWidth
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.height().coerceAtLeast(1), View.MeasureSpec.AT_MOST)
        )
        lp.width = width
        val height = view.measuredHeight.coerceAtLeast(1)
        val desiredX = when {
            !beside -> anchor.centerX() - width / 2
            useRight -> anchor.right + padding
            else -> anchor.left - padding - width
        }
        val desiredY = if (beside) {
            anchor.centerY() - height / 2
        } else if (anchor.bottom + padding + height <= screen.height() - padding) {
            anchor.bottom + padding
        } else {
            anchor.top - padding - height
        }
        lp.x = desiredX.coerceIn(padding, (screen.width() - width - padding).coerceAtLeast(padding))
        lp.y = desiredY.coerceIn(padding, (screen.height() - height - padding).coerceAtLeast(padding))
        try {
            if (added) {
                wm.updateViewLayout(view, lp)
            } else {
                wm.addView(view, lp)
                added = true
            }
        } catch (_: RuntimeException) {
            hide()
        }
    }

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

    private fun hide() {
        if (!added) return
        try { wm.removeView(view) } catch (_: RuntimeException) { }
        added = false
    }

    fun dispose() {
        disposed = true
        handler.removeCallbacks(dismissMessage)
        removeMessage()
        hide()
    }

    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wm.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
}
