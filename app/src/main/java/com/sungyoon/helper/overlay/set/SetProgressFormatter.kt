package com.sungyoon.helper.overlay.set

import android.content.Context
import com.sungyoon.helper.R
import com.sungyoon.helper.service.set.SetRunState
import com.sungyoon.helper.service.set.SetStopReason
import java.util.Locale
import kotlin.math.ceil

object SetProgressFormatter {
    fun error(context: Context, state: SetRunState): String? = when (state.stopReason) {
        SetStopReason.EMPTY -> context.getString(R.string.set_empty_message)
        SetStopReason.GESTURE_FAILED -> context.getString(R.string.set_failed_message, state.currentItem?.name.orEmpty())
        SetStopReason.INTERRUPTED -> context.getString(R.string.set_interrupted_message)
        SetStopReason.CANCELLED, SetStopReason.COMPLETED, null -> null
    }

    internal fun seconds(context: Context, milliseconds: Long): String =
        context.getString(R.string.set_seconds_value, String.format(Locale.KOREA, "%.1f", ceil(milliseconds.coerceAtLeast(0) / 100.0) / 10.0))
}
