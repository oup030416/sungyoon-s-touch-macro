package com.sungyoon.helper.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class HighlightingPoint(
    val id: String = UUID.randomUUID().toString(),
    val x: Float,          // 화면 절대 좌표(px)
    val y: Float,          // 화면 절대 좌표(px)
    val index: Int,        // 실행 순서(작을수록 먼저)
    val delayMs: Long,     // 이 포인트를 보여주는(및 탭 수행 후 대기하는) 시간
    val actionType: String = ACTION_TYPE_TAP,
    val dragToX: Float = x,
    val dragToY: Float = y,
    val dragDurationMs: Long = DEFAULT_DRAG_DURATION_MS
) {
    companion object {
        const val ACTION_TYPE_TAP = "tap"
        const val ACTION_TYPE_DRAG = "drag"
        const val DEFAULT_DRAG_DURATION_MS = 1000L
    }
}
