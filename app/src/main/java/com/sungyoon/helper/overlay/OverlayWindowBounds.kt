package com.sungyoon.helper.overlay

import android.os.Build
import android.view.View
import android.view.WindowManager

/** Pointer coordinates use the display; controls handle their own safe insets. */
internal fun configureFullScreenOverlayBounds(view: View, params: WindowManager.LayoutParams) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        params.setFitInsetsTypes(0)
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else {
        @Suppress("DEPRECATION")
        view.systemUiVisibility = view.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
