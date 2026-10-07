package com.sungyoon.helper.overlay.set

import android.content.Context
import com.sungyoon.helper.R
import com.sungyoon.helper.service.set.SetPhase
import com.sungyoon.helper.service.set.SetRunState
import com.sungyoon.helper.service.set.SetStopReason
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

object SetProgressFormatter {
    fun title(context: Context, state: SetRunState): String = when {
        state.stopping -> context.getString(R.string.set_status_stopping)
        !state.active -> context.getString(R.string.set_status_idle)
        state.paused -> context.getString(R.string.set_paused_title,
            state.currentItem?.name ?: context.getString(R.string.set_title))
        else -> state.currentItem?.name ?: context.getString(R.string.set_title)
    }

    fun detail(context: Context, state: SetRunState): String {
        if (!state.active) return error(context, state).orEmpty()
        val percentage = context.getString(R.string.set_progress_percent, (state.progress.coerceIn(0f, 1f) * 100).roundToInt())
        return when (state.phase) {
            SetPhase.TOUCH -> context.getString(R.string.set_progress_touch,
                state.pointerOffset.coerceAtMost(state.pointerCount), state.pointerCount, percentage)
            SetPhase.RUN, SetPhase.REST -> context.getString(R.string.set_progress_reserved,
                state.cycleCurrent, state.cycleTotal,
                context.getString(if (state.phase == SetPhase.REST) R.string.reservation_phase_rest else R.string.reservation_phase_run),
                seconds(context, state.remainingMs), percentage)
            SetPhase.WAIT -> context.getString(R.string.set_progress_wait, seconds(context, state.remainingMs), percentage)
            SetPhase.IDLE -> percentage
        }
    }

    fun error(context: Context, state: SetRunState): String? = when (state.stopReason) {
        SetStopReason.EMPTY -> context.getString(R.string.set_empty_message)
        SetStopReason.GESTURE_FAILED -> context.getString(R.string.set_failed_message, state.currentItem?.name.orEmpty())
        SetStopReason.INTERRUPTED -> context.getString(R.string.set_interrupted_message)
        SetStopReason.CANCELLED, null -> null
    }

    internal fun seconds(context: Context, milliseconds: Long): String =
        context.getString(R.string.set_seconds_value, String.format(Locale.KOREA, "%.1f", ceil(milliseconds.coerceAtLeast(0) / 100.0) / 10.0))
}
