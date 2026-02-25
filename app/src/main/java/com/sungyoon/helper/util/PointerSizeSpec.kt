package com.sungyoon.helper.util

object PointerSizeSpec {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 10
    const val DEFAULT_LEVEL = 8

    // Keep this table aligned with rendered pointer radius in overlay pointer UI.
    private val LEVEL_TO_RADIUS_DP = intArrayOf(8, 10, 12, 14, 16, 18, 20, 22, 24, 26)

    fun radiusDpForLevel(level: Int): Int {
        val clamped = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        return LEVEL_TO_RADIUS_DP[clamped - 1]
    }
}
