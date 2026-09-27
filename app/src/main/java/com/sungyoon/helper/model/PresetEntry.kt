package com.sungyoon.helper.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PresetEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAtEpochMs: Long,
    val points: List<PresetPoint>,
    val autoNameOrdinal: Int,
    val settings: PresetSettings? = null
)

@Serializable
data class PresetSettings(
    val tapIntervalMs: Long = 1000L,
    val dragDurationMs: Long = 1000L,
    val randomRadiusDp: Int = 5,
    val repeatEnabled: Boolean = true,
    val touchAnimationEnabled: Boolean = true
)

@Serializable
data class PresetPoint(
    val index: Int,
    val actionType: String = HighlightingPoint.ACTION_TYPE_TAP,
    val x: Float,
    val y: Float,
    val dragToX: Float = x,
    val dragToY: Float = y
)
