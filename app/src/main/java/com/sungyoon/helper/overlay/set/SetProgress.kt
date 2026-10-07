package com.sungyoon.helper.overlay.set

import com.sungyoon.helper.service.set.SetRunState
import kotlin.math.roundToInt

/** Each item occupies one equal segment of the current traversal, regardless of duration. */
internal object SetProgress {
    fun percent(state: SetRunState): Int {
        if (!state.active || state.currentItem == null || state.itemCount <= 0 || state.itemPosition <= 0) return 0
        val position = state.itemPosition.coerceAtMost(state.itemCount)
        val itemProgress = if (state.progress.isFinite()) state.progress.coerceIn(0f, 1f).toDouble() else 0.0
        return (((position - 1).toDouble() + itemProgress) / state.itemCount * 100.0)
            .roundToInt().coerceIn(0, 100)
    }
}
