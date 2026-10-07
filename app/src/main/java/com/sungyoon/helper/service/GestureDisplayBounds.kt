package com.sungyoon.helper.service

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager

/** Read current default-display coverage rather than an inset application resource viewport. */
internal fun currentGestureDisplayBounds(context: Context): GestureCoordinateBounds {
    val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val display = checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val displayContext = context.createDisplayContext(display)
        val windows = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = windows.maximumWindowMetrics.bounds
        if (bounds.width() > 0 && bounds.height() > 0) {
            return GestureCoordinateBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
    }

    // This API includes decor and follows current rotation on the supported pre-R devices.
    @Suppress("DEPRECATION")
    val size = Point().also(display::getRealSize)
    return GestureCoordinateBounds(0, 0, size.x, size.y)
}
