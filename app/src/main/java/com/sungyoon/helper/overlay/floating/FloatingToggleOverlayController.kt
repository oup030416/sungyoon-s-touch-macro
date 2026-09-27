package com.sungyoon.helper.overlay.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.sungyoon.helper.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class FloatingToggleOverlayController(
    private val context: Context,
    private val overlayType: Int,
    private val onToggle: () -> Unit,
    private val isOn: () -> Boolean,
    private val onHoldToggle: (Boolean) -> Unit = {},
    private val onHidden: () -> Unit = {},
    private val onConfigurationChanged: () -> Unit = {},
    private val onPhysicalTouch: () -> Unit = {}
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: FloatingToggleView? = null
    private var container: LinearLayout? = null
    private var holdButton: TextView? = null
    private var added = false
    private var holdActive = false
    private var holdRunning = false
    private var holdSupported = false
    private var pendingHoldTarget: Boolean? = null

    private var trashView: TrashDropView? = null
    private var trashAdded = false

    private val density = context.resources.displayMetrics.density
    private val sizePx = (56f * density).roundToInt()
    private val holdButtonHeightPx = (36f * density).roundToInt()
    private val holdButtonGapPx = (4f * density).roundToInt()
    private val holdExtraHeightPx get() = if (holdActive) holdButtonHeightPx + holdButtonGapPx else 0
    private val windowHeightPx get() = sizePx + holdExtraHeightPx
    private val trashSizePx = (120f * density).roundToInt()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var moved = false
    private var dragging = false

    private var dragScreenW = 0
    private var dragScreenH = 0

    private val edgePaddingPx = (8f * density).roundToInt()
    private val trashBottomMarginPx = (28f * density).roundToInt()

    private var trashCenterX = 0f
    private var trashCenterY = 0f

    // Track the previous bounds to keep the icon near its relative position on rotation.
    private var lastScreenW = 0
    private var lastScreenH = 0

    private var configReceiver: BroadcastReceiver? = null
    private var configReceiverRegistered = false

    private val lp = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = edgePaddingPx
        y = (180f * density).roundToInt()
    }

    private val trashLp = WindowManager.LayoutParams(
        trashSizePx,
        trashSizePx,
        overlayType,
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

    fun isShowing(): Boolean = added

    fun setHoldState(active: Boolean, running: Boolean, supported: Boolean) {
        val previousExtraHeight = holdExtraHeightPx
        holdActive = active
        holdRunning = active && running
        holdSupported = supported
        lp.y -= holdExtraHeightPx - previousExtraHeight
        lp.height = windowHeightPx
        updateHoldButton()
        val (screenW, screenH) = getScreenSizePx()
        clampIntoScreen(screenW, screenH)
        updateContainerLayout()
    }

    fun show() {
        if (added) return

        val group = object : LinearLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) pendingHoldTarget = null
                // Android 16 QPR2 can cancel injected contacts without a result callback.
                // Observe real DOWNs without intercepting touches outside this small window.
                if ((event.actionMasked == MotionEvent.ACTION_OUTSIDE ||
                            event.actionMasked == MotionEvent.ACTION_DOWN) &&
                    event.device?.isVirtual == false) {
                    // Preserve the user's intended toggle before cancellation changes its state.
                    if (holdRunning && event.actionMasked == MotionEvent.ACTION_DOWN &&
                        event.x >= 0f && event.x < width && event.y >= 0f && event.y < holdButtonHeightPx)
                        pendingHoldTarget = false
                    onPhysicalTouch()
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val button = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setSingleLine(true)
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (holdActive && holdSupported) {
                    val target = pendingHoldTarget ?: !holdRunning
                    pendingHoldTarget = null
                    onHoldToggle(target)
                }
            }
        }
        var toggleDownX = 0f
        var toggleDownY = 0f
        var toggleMoved = false
        button.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A physical touch can cancel the injected gesture before ACTION_UP.
                    if (pendingHoldTarget == null) pendingHoldTarget = !holdRunning
                    toggleDownX = event.rawX
                    toggleDownY = event.rawY
                    toggleMoved = false
                    target.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - toggleDownX) > touchSlop || abs(event.rawY - toggleDownY) > touchSlop) {
                        toggleMoved = true
                        target.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    target.isPressed = false
                    if (!toggleMoved && event.x >= 0 && event.x <= target.width &&
                        event.y >= 0 && event.y <= target.height
                    ) target.performClick()
                    pendingHoldTarget = null
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    target.isPressed = false
                    pendingHoldTarget = null
                    true
                }
                else -> false
            }
        }
        group.addView(button, LinearLayout.LayoutParams(sizePx, holdButtonHeightPx).apply {
            bottomMargin = holdButtonGapPx
        })
        holdButton = button
        updateHoldButton()

        val v = FloatingToggleView(context) { isOn() }.apply {
            setOnClickListener {
                onToggle()
                invalidate()
            }
        }
        group.addView(v, LinearLayout.LayoutParams(sizePx, sizePx))
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    dragging = false

                    val (sw, sh) = getScreenSizePx()
                    dragScreenW = sw
                    dragScreenH = sh

                    lastScreenW = sw
                    lastScreenH = sh

                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downX = lp.x
                    downY = lp.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX)
                    val dy = (ev.rawY - downRawY)

                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        setDraggingState(true)
                    }

                    val desiredX = (downX + dx).roundToInt()
                    val desiredY = (downY + dy).roundToInt()

                    lp.x = desiredX.coerceIn(
                        edgePaddingPx,
                        (dragScreenW - sizePx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
                    )
                    lp.y = desiredY.coerceIn(
                        edgePaddingPx,
                        (dragScreenH - windowHeightPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
                    )

                    updateContainerLayout()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        true
                    } else {
                        val dropped = ev.actionMasked == MotionEvent.ACTION_UP && isDroppedOnTrash()
                        setDraggingState(false)
                        if (dropped) {
                            hide()
                        } else {
                            snapToEdge()
                            val (sw, sh) = getScreenSizePx()
                            lastScreenW = sw
                            lastScreenH = sh
                        }
                        true
                    }
                }

                else -> false
            }
        }

        lp.height = windowHeightPx
        val (sw, sh) = getScreenSizePx()
        lastScreenW = sw
        lastScreenH = sh
        clampIntoScreen(sw, sh)
        try {
            wm.addView(group, lp)
            view = v
            container = group
            added = true
        } catch (_: Throwable) {
            view = null
            container = null
            holdButton = null
            added = false
            return
        }

        registerConfigReceiver()
    }

    fun hide() {
        hideTrash()
        unregisterConfigReceiver()

        if (!added) return
        container?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        view = null
        container = null
        holdButton = null
        pendingHoldTarget = null
        added = false
        dragging = false
        if (trashAdded) {
            trashView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
            trashView = null
            trashAdded = false
        }
        onHidden()
    }

    fun invalidate() {
        view?.invalidate()
    }

    private fun updateHoldButton() {
        holdButton?.apply {
            visibility = if (holdActive) View.VISIBLE else View.GONE
            isEnabled = holdSupported
            alpha = if (holdSupported) 1f else 0.45f
            text = context.getString(if (holdRunning) R.string.hold_toggle_on else R.string.hold_toggle_off)
            contentDescription = context.getString(
                when {
                    !holdSupported -> R.string.hold_requires_android_8
                    holdRunning -> R.string.hold_toggle_on_description
                    else -> R.string.hold_toggle_off_description
                }
            )
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor(if (holdRunning) "#256D48" else "#CC222238"))
                setStroke((density * 1.5f).roundToInt(), Color.parseColor(if (holdRunning) "#73E6A5" else "#88889B"))
            }
        }
    }

    private fun updateContainerLayout() {
        if (!added) return
        container?.let { try { wm.updateViewLayout(it, lp) } catch (_: Throwable) {} }
    }

    private fun setDraggingState(on: Boolean) {
        if (dragging == on) return
        dragging = on
        if (on) showTrash() else hideTrash()
    }

    private fun showTrash() {
        val v = view ?: return
        ensureTrashAdded()
        trashView?.let { tv ->
            val sw = dragScreenW.takeIf { it > 0 } ?: getScreenSizePx().first
            val sh = dragScreenH.takeIf { it > 0 } ?: getScreenSizePx().second

            val x = (sw / 2) - (trashSizePx / 2)
            val y = (sh - trashSizePx - trashBottomMarginPx).coerceAtLeast(edgePaddingPx)

            trashLp.x = x
            trashLp.y = y
            trashCenterX = x + trashSizePx / 2f
            trashCenterY = y + trashSizePx / 2f

            try { wm.updateViewLayout(tv, trashLp) } catch (_: Throwable) {}

            tv.visibility = View.VISIBLE
            tv.alpha = 0f
            tv.scaleX = 0.92f
            tv.scaleY = 0.92f
            tv.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .start()
        }

        v.animate().cancel()
        v.animate().alpha(0.9f).setDuration(120L).start()
    }

    private fun hideTrash() {
        trashView?.let { tv ->
            tv.animate().cancel()
            tv.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(120L)
                .withEndAction { tv.visibility = View.INVISIBLE }
                .start()
        }
        view?.animate()?.cancel()
        view?.animate()?.alpha(1f)?.setDuration(120L)?.start()
    }

    private fun ensureTrashAdded() {
        if (trashAdded) return
        val tv = TrashDropView(context).apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        trashView = tv
        try {
            wm.addView(tv, trashLp)
            trashAdded = true
        } catch (_: Throwable) {
            trashView = null
            trashAdded = false
        }
    }

    private fun isDroppedOnTrash(): Boolean {
        val btnCx = lp.x + sizePx / 2f
        val btnCy = lp.y + holdExtraHeightPx + sizePx / 2f
        val radius = (trashSizePx / 2f) * 0.9f
        return hypot(btnCx - trashCenterX, btnCy - trashCenterY) <= radius
    }

    private fun snapToEdge() {
        val (screenW, screenH) = getScreenSizePx()
        val left = edgePaddingPx
        val right = (screenW - sizePx - edgePaddingPx).coerceAtLeast(left)
        lp.x = if (lp.x < screenW / 2) left else right

        lp.y = lp.y.coerceIn(
            edgePaddingPx,
            (screenH - windowHeightPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        )

        updateContainerLayout()

        lastScreenW = screenW
        lastScreenH = screenH
    }

    private fun clampIntoScreen(screenW: Int, screenH: Int) {
        lp.x = lp.x.coerceIn(
            edgePaddingPx,
            (screenW - sizePx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        )
        lp.y = lp.y.coerceIn(
            edgePaddingPx,
            (screenH - windowHeightPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        )
    }

    private fun remapPositionOnRotation(newW: Int, newH: Int) {
        val oldW = lastScreenW
        val oldH = lastScreenH

        if (oldW > 0 && oldH > 0) {
            val oldCx = lp.x + sizePx / 2f
            val oldCy = lp.y + holdExtraHeightPx + sizePx / 2f

            val rx = (oldCx / oldW.toFloat()).coerceIn(0f, 1f)
            val ry = (oldCy / oldH.toFloat()).coerceIn(0f, 1f)

            val newCx = rx * newW
            val newCy = ry * newH

            lp.x = (newCx - sizePx / 2f).roundToInt()
            lp.y = (newCy - holdExtraHeightPx - sizePx / 2f).roundToInt()
        }

        clampIntoScreen(newW, newH)
        lastScreenW = newW
        lastScreenH = newH
    }

    private fun registerConfigReceiver() {
        if (configReceiverRegistered) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (!added) return
                if (intent?.action != Intent.ACTION_CONFIGURATION_CHANGED) return

                onConfigurationChanged()
                val (sw, sh) = getScreenSizePx()
                setDraggingState(false)
                moved = true
                pendingHoldTarget = null
                dragScreenW = sw
                dragScreenH = sh
                remapPositionOnRotation(sw, sh)
                downX = lp.x
                downY = lp.y
                updateContainerLayout()
            }
        }

        configReceiver = receiver
        try {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))
            configReceiverRegistered = true
        } catch (_: Throwable) {
            configReceiver = null
            configReceiverRegistered = false
        }
    }

    private fun unregisterConfigReceiver() {
        if (!configReceiverRegistered) return
        try {
            configReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Throwable) {
        } finally {
            configReceiver = null
            configReceiverRegistered = false
        }
    }

    private fun getScreenSizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            dm.widthPixels to dm.heightPixels
        }
    }
}
