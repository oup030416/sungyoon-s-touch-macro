package com.sungyoon.helper.overlay.set

import com.sungyoon.helper.model.SetItem
import java.math.BigDecimal
import java.util.Locale

/** Decimal parsing keeps the 0.1-second constraint exact, including pasted values. */
internal object SetWaitDurationInput {
    fun parseSeconds(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val duration = runCatching { BigDecimal(text).movePointRight(3).longValueExact() }.getOrNull() ?: return null
        return duration.takeIf { SetItem.Wait.isValidDuration(it) }
    }

    fun formatSeconds(milliseconds: Long): String = String.format(Locale.KOREA, "%.1f", milliseconds / 1000.0)
}
