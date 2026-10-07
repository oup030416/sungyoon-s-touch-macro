package com.sungyoon.helper.ui

import android.content.Context
import android.view.View
import android.widget.ScrollView

/** Follow the user's drag directly and stop when their finger leaves the screen. */
class DirectScrollView(context: Context) : ScrollView(context) {
    init {
        overScrollMode = View.OVER_SCROLL_NEVER
        isSmoothScrollingEnabled = false
    }

    override fun fling(velocityY: Int) = Unit
}
