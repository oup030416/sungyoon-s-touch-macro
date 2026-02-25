package com.sungyoon.helper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ✅ 기존 기능 그대로: dispatchGesture 기반 "탭" 수행
 * - start: 0ms
 * - duration: 50ms
 */
suspend fun AccessibilityService.dispatchTap(x: Float, y: Float): Boolean {
    return suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )

        if (!ok && cont.isActive) cont.resume(false)
    }
}

suspend fun AccessibilityService.dispatchDrag(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    durationMs: Long
): Boolean {
    return suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val duration = durationMs.coerceAtLeast(1L)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration, false)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )

        if (!ok && cont.isActive) cont.resume(false)
    }
}
