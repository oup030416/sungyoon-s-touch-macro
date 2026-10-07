package com.sungyoon.helper.overlay.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class FloatingToggleOverlayController(
    private val context: Context,
    private val overlayType: Int,
    private val onToggle: () -> Unit,
    private val isOn: () -> Boolean
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: FloatingToggleView? = null
    private var added = false

    private var trashView: TrashDropView? = null
    private var trashAdded = false

    private val density = context.resources.displayMetrics.density
    private val sizePx = (56f * density).roundToInt()
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

    // ✅ 회전 전/후 비율 기반 재매핑용
    private var lastScreenW = 0
    private var lastScreenH = 0

    // ✅ 회전 이벤트 수신
    private var configReceiver: BroadcastReceiver? = null
    private var configReceiverRegistered = false

    private val lp = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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

    fun show() {
        if (added) return

        val v = FloatingToggleView(context) { isOn() }
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    dragging = false

                    val (sw, sh) = getScreenSizePx()
                    dragScreenW = sw
                    dragScreenH = sh

                    // ✅ 드래그 시작 시점의 화면 크기도 저장
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
                        (dragScreenH - sizePx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
                    )

                    try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        onToggle()
                        v.invalidate()
                        true
                    } else {
                        val dropped = isDroppedOnTrash()
                        setDraggingState(false)
                        if (dropped) {
                            hide()
                        } else {
                            snapToEdge(v)
                            // ✅ 드래그 종료 후 화면 크기 갱신
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

        // ✅ show 시점에 현재 화면 안으로 보정
        val (sw, sh) = getScreenSizePx()
        lastScreenW = sw
        lastScreenH = sh
        clampIntoScreen(sw, sh)
        try {
            wm.addView(v, lp)
            view = v
            added = true
        } catch (_: Throwable) {
            view = null
            added = false
            return
        }

        registerConfigReceiver()
    }

    fun hide() {
        hideTrash()
        unregisterConfigReceiver()

        if (!added) return
        view?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        view = null
        added = false
        dragging = false
    }

    fun invalidate() {
        view?.invalidate()
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
        val btnCy = lp.y + sizePx / 2f
        val radius = (trashSizePx / 2f) * 0.9f
        return hypot(btnCx - trashCenterX, btnCy - trashCenterY) <= radius
    }

    private fun snapToEdge(v: FloatingToggleView) {
        val (screenW, screenH) = getScreenSizePx()
        val left = edgePaddingPx
        val right = (screenW - sizePx - edgePaddingPx).coerceAtLeast(left)
        lp.x = if (lp.x < screenW / 2) left else right

        // ✅ y도 화면 안으로 강제 보정 (회전 직후/키보드/컷아웃 등)
        lp.y = lp.y.coerceIn(
            edgePaddingPx,
            (screenH - sizePx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        )

        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}

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
            (screenH - sizePx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
        )
    }

    // ✅ 회전 시: "이전 화면에서의 상대 위치"를 새 화면으로 재매핑 후 clamp
    private fun remapPositionOnRotation(newW: Int, newH: Int) {
        val oldW = lastScreenW
        val oldH = lastScreenH

        if (oldW > 0 && oldH > 0) {
            val oldCx = lp.x + sizePx / 2f
            val oldCy = lp.y + sizePx / 2f

            val rx = (oldCx / oldW.toFloat()).coerceIn(0f, 1f)
            val ry = (oldCy / oldH.toFloat()).coerceIn(0f, 1f)

            val newCx = rx * newW
            val newCy = ry * newH

            lp.x = (newCx - sizePx / 2f).roundToInt()
            lp.y = (newCy - sizePx / 2f).roundToInt()
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

                val v = view ?: return
                val (sw, sh) = getScreenSizePx()

                // ✅ 드래그 중이면 화면/좌표가 흔들릴 수 있어 종료 시점에 보정
                if (dragging) {
                    dragScreenW = sw
                    dragScreenH = sh
                    return
                }

                remapPositionOnRotation(sw, sh)
                try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
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
