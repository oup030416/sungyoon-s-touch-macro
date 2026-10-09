package com.sungyoon.helper.service.set

import com.sungyoon.helper.model.SetItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SetPhase { IDLE, TOUCH, RUN, REST, WAIT }
enum class SetStopReason { EMPTY, GESTURE_FAILED, CANCELLED, INTERRUPTED, COMPLETED }

data class SetGestureOptions(
    val intervalMs: Long = 1000L,
    val dragDurationMs: Long = 1000L,
)

data class SetRunState(
    val setId: String? = null,
    val active: Boolean = false,
    val paused: Boolean = false,
    val stopping: Boolean = false,
    val currentItem: SetItem? = null,
    val pass: Long = 1L,
    val itemPosition: Int = 0,
    val itemCount: Int = 0,
    val phase: SetPhase = SetPhase.IDLE,
    val pointerOffset: Int = 0,
    val pointerCount: Int = 0,
    val cycleCurrent: Int = 1,
    val cycleTotal: Int = 1,
    val remainingMs: Long = 0L,
    val phaseDurationMs: Long = 0L,
    val progress: Float = 0f,
    val elapsedMs: Long = 0L,
    val stopReason: SetStopReason? = null,
)

/** In-process bridge only: interrupted work is never restored from disk. */
object SetRuntime {
    private val mutableState = MutableStateFlow(SetRunState())
    val state: StateFlow<SetRunState> = mutableState.asStateFlow()
    val active: Boolean get() = state.value.active

    fun update(state: SetRunState) {
        mutableState.value = state
    }
}
