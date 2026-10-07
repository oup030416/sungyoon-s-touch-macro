package com.sungyoon.helper.service.set

import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.SetItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** Owns set timing and ordering; Android gesture and UI code remain outside this class. */
class SetRunner(
    private val scope: CoroutineScope,
    private val items: StateFlow<List<SetItem>>,
    private val options: () -> SetGestureOptions,
    private val nowMs: () -> Long,
    private val execute: suspend (HighlightingPoint, String) -> Boolean,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val gate = Any()
    private val mutableState = MutableStateFlow(SetRunState())
    val state: StateFlow<SetRunState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var cursor = SetCursor()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var stopRequested: SetStopReason? = null
    private var pauseRequested = false
    private var inGesture = false
    private var itemExecuting = false
    private var pausedAtMs: Long? = null
    private var accumulatedPauseMs = 0L
    private var startedAtMs = 0L
    private var timing: PhaseTiming? = null
    private var disposed = false

    private data class PhaseTiming(
        val endsAtMs: Long,
        val durationMs: Long,
        val progressBase: Float,
        val progressWeight: Float,
    )

    fun start(paused: Boolean = false): Boolean {
        val nextJob: Job
        synchronized(gate) {
            if (disposed || mutableState.value.active || job?.isActive == true || !scope.isActive) return false
            if (items.value.none { it.isExecutable }) {
                mutableState.value = SetRunState(stopReason = SetStopReason.EMPTY)
                return false
            }
            stopRequested = null
            pauseRequested = paused
            inGesture = false
            itemExecuting = false
            pausedAtMs = if (paused) nowMs() else null
            accumulatedPauseMs = 0L
            startedAtMs = activeNowLocked()
            timing = null
            cursor = SetCursor()
            mutableState.value = SetRunState(active = true, paused = paused, itemCount = items.value.size)
            nextJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runSet()
                } catch (cancelled: CancellationException) {
                    synchronized(gate) {
                        if (stopRequested == null) stopRequested = SetStopReason.INTERRUPTED
                    }
                    throw cancelled
                } finally {
                    finish()
                }
            }
            job = nextJob
            nextJob.invokeOnCompletion {
                synchronized(gate) {
                    // A lazy job can be disposed before its body and finally block run.
                    if (job === nextJob) finish()
                }
            }
        }
        nextJob.start()
        return true
    }

    fun pause() {
        synchronized(gate) {
            if (!mutableState.value.active || pauseRequested || stopRequested != null) return
            pauseRequested = true
            pausedAtMs = nowMs()
            refreshTimingLocked()
            mutableState.value = mutableState.value.copy(paused = !inGesture)
        }
        wake.trySend(Unit)
    }

    fun resume() {
        synchronized(gate) {
            if (!mutableState.value.active || !pauseRequested || stopRequested != null) return
            pausedAtMs?.let { accumulatedPauseMs += (nowMs() - it).coerceAtLeast(0L) }
            pausedAtMs = null
            pauseRequested = false
            mutableState.value = mutableState.value.copy(paused = false)
        }
        wake.trySend(Unit)
    }

    /** New gestures stop immediately; an already dispatched gesture is allowed to settle. */
    fun cancel() = requestStop(SetStopReason.CANCELLED)

    suspend fun stopAndJoin() {
        cancel()
        val running = synchronized(gate) { job }
        running?.join()
    }

    /** Teardown also cancels suspended waits; runtime is never restored after reconnecting. */
    fun dispose() {
        synchronized(gate) { disposed = true }
        requestStop(SetStopReason.INTERRUPTED)
        synchronized(gate) {
            if (job == null) mutableState.value = SetRunState(stopReason = SetStopReason.INTERRUPTED)
            job?.cancel()
        }
    }

    private fun requestStop(reason: SetStopReason) {
        synchronized(gate) {
            if (!mutableState.value.active || stopRequested != null) return
            stopRequested = reason
            refreshTimingLocked()
            mutableState.value = mutableState.value.copy(stopping = true)
        }
        wake.trySend(Unit)
    }

    private suspend fun runSet() = coroutineScope {
        val observer = launch {
            items.collect { latest ->
                cursor.observe(latest)
                val empty = synchronized(gate) {
                    if (mutableState.value.active) {
                        clearDeletedItemLocked(latest)
                        mutableState.value = mutableState.value.copy(
                            itemCount = latest.size,
                            itemPosition = if (mutableState.value.currentItem == null) 0
                                else cursor.position(latest),
                        )
                    }
                    (!itemExecuting || mutableState.value.currentItem == null) && latest.none { it.isExecutable }
                }
                if (empty) requestStop(SetStopReason.EMPTY)
                wake.trySend(Unit)
            }
        }
        try {
            // A paused startup has not entered any item; choose the latest top item on resume.
            if (!awaitControls()) return@coroutineScope
            var next = cursor.first(items.value)
            while (currentCoroutineContext().isActive && awaitControls()) {
                if (next == null) {
                    if (items.value.none { it.isExecutable }) {
                        requestStop(SetStopReason.EMPTY)
                        break
                    }
                    synchronized(gate) {
                        mutableState.value = mutableState.value.copy(pass = mutableState.value.pass + 1L)
                    }
                    next = cursor.first(items.value)
                }
                val candidate = next ?: continue
                val current = items.value.firstOrNull { it.id == candidate.id }
                if (current == null || !current.isExecutable) {
                    next = cursor.next(items.value)
                    continue
                }
                val frozen = freeze(current)
                synchronized(gate) {
                    timing = null
                    itemExecuting = true
                    mutableState.value = mutableState.value.copy(
                        currentItem = frozen,
                        itemPosition = cursor.position(items.value),
                        itemCount = items.value.size,
                        phase = when (frozen) {
                            is SetItem.Touch -> SetPhase.TOUCH
                            is SetItem.Reserved -> SetPhase.RUN
                            is SetItem.Wait -> SetPhase.WAIT
                        },
                        pointerOffset = 0,
                        pointerCount = frozen.points.size,
                        cycleCurrent = 1,
                        cycleTotal = (frozen as? SetItem.Reserved)?.reservation?.repeatCount ?: 1,
                        remainingMs = 0L,
                        phaseDurationMs = 0L,
                        progress = 0f,
                    )
                }
                when (frozen) {
                    is SetItem.Touch -> runTouch(frozen)
                    is SetItem.Reserved -> runReserved(frozen)
                    is SetItem.Wait -> {
                        val end = beginPhase(SetPhase.WAIT, frozen.durationMs, 0f, 1f)
                        waitUntil(end, frozen.id)
                    }
                }
                synchronized(gate) { itemExecuting = false }
                if (!awaitControls()) break
                next = cursor.next(items.value)
            }
        } finally {
            observer.cancel()
        }
    }

    private suspend fun runTouch(item: SetItem.Touch) {
        for ((offset, point) in item.points.withIndex()) {
            if (!dispatch(point, offset, item.id)) return
            synchronized(gate) {
                if (mutableState.value.currentItem?.id == item.id) {
                    mutableState.value = mutableState.value.copy(pointerOffset = offset + 1)
                }
            }
            // The final pointer has the same interval as every other pointer.
            val interval = options().intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
            val end = beginPhase(
                SetPhase.TOUCH,
                interval,
                offset.toFloat() / item.points.size,
                1f / item.points.size,
            )
            if (!waitUntil(end, item.id)) return
        }
    }

    private suspend fun runReserved(item: SetItem.Reserved) {
        val config = item.reservation
        val runMs = config.runSeconds * 1000L
        val restMs = config.restSeconds * 1000L
        val totalMs = (runMs + restMs) * config.repeatCount
        var offset = 0
        for (cycle in 1..config.repeatCount) {
            synchronized(gate) {
                mutableState.value = mutableState.value.copy(cycleCurrent = cycle)
            }
            val completedMs = (cycle - 1L) * (runMs + restMs)
            val runEnd = beginPhase(SetPhase.RUN, runMs, completedMs.toFloat() / totalMs, runMs.toFloat() / totalMs)
            while (awaitControls(item.id)) {
                val remaining = remainingUntil(runEnd)
                if (remaining <= 0L) break
                val point = item.points[offset]
                val prepared = prepare(point)
                if (prepared.isEffectiveDrag && prepared.dragDurationMs > remaining) {
                    if (!waitUntil(runEnd, item.id)) return
                    break
                }
                if (!dispatch(prepared, offset, item.id, prepared = true)) return
                offset = (offset + 1) % item.points.size
                synchronized(gate) {
                    if (mutableState.value.currentItem?.id == item.id) {
                        mutableState.value = mutableState.value.copy(pointerOffset = offset)
                    }
                }
                val intervalEnd = activeNow() + options().intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
                if (!waitUntil(minOf(intervalEnd, runEnd), item.id)) return
            }
            if (!awaitControls(item.id)) return
            val restEnd = beginPhase(
                SetPhase.REST,
                restMs,
                (completedMs + runMs).toFloat() / totalMs,
                restMs.toFloat() / totalMs,
            )
            // Rest also follows the final execution cycle.
            if (!waitUntil(restEnd, item.id)) return
        }
    }

    private suspend fun dispatch(
        point: HighlightingPoint,
        offset: Int,
        itemId: String,
        prepared: Boolean = false,
    ): Boolean {
        while (true) {
            if (!awaitControls(itemId)) return false
            val entered = synchronized(gate) {
                if (stopRequested != null || pauseRequested || items.value.none { it.id == itemId }) false
                else {
                    inGesture = true
                    true
                }
            }
            if (entered) break
        }
        val succeeded = try {
            execute(if (prepared) point else prepare(point), "${offset + 1}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            synchronized(gate) {
                inGesture = false
                refreshTimingLocked()
                if (pauseRequested && stopRequested == null) {
                    mutableState.value = mutableState.value.copy(paused = true)
                }
            }
        }
        if (!succeeded) requestStop(SetStopReason.GESTURE_FAILED)
        return succeeded && synchronized(gate) {
            stopRequested == null && items.value.any { it.id == itemId }
        }
    }

    private fun prepare(point: HighlightingPoint): HighlightingPoint =
        point.copy(dragDurationMs = options().dragDurationMs.coerceAtLeast(MIN_INTERVAL_MS))

    private suspend fun awaitControls(itemId: String? = null): Boolean {
        while (currentCoroutineContext().isActive) {
            val ready = synchronized(gate) {
                val latest = items.value
                clearDeletedItemLocked(latest)
                if ((!itemExecuting || mutableState.value.currentItem == null) && latest.none { it.isExecutable }) {
                    requestStop(SetStopReason.EMPTY)
                }
                if (stopRequested != null || (itemId != null && latest.none { it.id == itemId })) return false
                refreshTimingLocked()
                !pauseRequested
            }
            if (ready) return true
            wake.receive()
        }
        return false
    }

    private suspend fun waitUntil(endActiveMs: Long, itemId: String): Boolean {
        while (awaitControls(itemId)) {
            val remaining = remainingUntil(endActiveMs)
            if (remaining <= 0L) return true
            waitSlice(minOf(TICK_MS, remaining))
        }
        return false
    }

    private suspend fun waitSlice(durationMs: Long) = coroutineScope {
        val sleeper = async { sleep(durationMs) }
        try {
            select<Unit> {
                sleeper.onAwait { }
                wake.onReceive { }
            }
        } finally {
            sleeper.cancel()
        }
    }

    private fun beginPhase(phase: SetPhase, durationMs: Long, progressBase: Float, progressWeight: Float): Long =
        synchronized(gate) {
            val duration = durationMs.coerceAtLeast(0L)
            val end = activeNowLocked() + duration
            clearDeletedItemLocked(items.value)
            if (mutableState.value.currentItem != null) {
                timing = PhaseTiming(end, duration, progressBase, progressWeight)
                mutableState.value = mutableState.value.copy(
                    phase = phase,
                    remainingMs = duration,
                    phaseDurationMs = duration,
                    progress = progressBase.coerceIn(0f, 1f),
                )
            }
            end
        }

    private fun clearDeletedItemLocked(latest: List<SetItem>) {
        val current = mutableState.value.currentItem ?: return
        if (latest.any { it.id == current.id }) return
        timing = null
        mutableState.value = mutableState.value.copy(
            currentItem = null,
            phase = SetPhase.IDLE,
            itemPosition = 0,
            pointerOffset = 0,
            pointerCount = 0,
            remainingMs = 0L,
            phaseDurationMs = 0L,
            progress = 0f,
        )
    }

    private fun refreshTimingLocked() {
        val state = mutableState.value
        if (!state.active) return
        val activeTime = activeNowLocked()
        val elapsed = (activeTime - startedAtMs).coerceAtLeast(0L)
        // A first gesture may not have phase timing yet, but opening the manager must capture its elapsed time.
        val current = timing
        if (current == null) {
            mutableState.value = state.copy(elapsedMs = elapsed)
            return
        }
        val remaining = (current.endsAtMs - activeTime).coerceIn(0L, current.durationMs)
        val portion = if (current.durationMs == 0L) 1f else 1f - remaining.toFloat() / current.durationMs
        mutableState.value = mutableState.value.copy(
            remainingMs = remaining,
            progress = (current.progressBase + current.progressWeight * portion).coerceIn(0f, 1f),
            elapsedMs = elapsed,
        )
    }

    private fun remainingUntil(endActiveMs: Long): Long = synchronized(gate) {
        refreshTimingLocked()
        (endActiveMs - activeNowLocked()).coerceAtLeast(0L)
    }

    private fun activeNow(): Long = synchronized(gate) { activeNowLocked() }

    private fun activeNowLocked(): Long =
        (pausedAtMs ?: nowMs()) - accumulatedPauseMs

    private fun freeze(item: SetItem): SetItem = when (item) {
        is SetItem.Touch -> item.copy(points = item.points.sortedBy { it.index }.map { it.copy() })
        is SetItem.Reserved -> item.copy(
            points = item.points.sortedBy { it.index }.map { it.copy() },
            reservation = item.reservation.normalized(),
        )
        is SetItem.Wait -> item.copy(
            durationMs = item.durationMs.coerceIn(SetItem.Wait.MIN_DURATION_MS, SetItem.Wait.MAX_DURATION_MS),
        )
    }

    private fun finish() {
        synchronized(gate) {
            val previous = mutableState.value
            val reason = stopRequested ?: SetStopReason.INTERRUPTED
            timing = null
            pausedAtMs = null
            pauseRequested = false
            inGesture = false
            itemExecuting = false
            job = null
            mutableState.value = if (reason == SetStopReason.GESTURE_FAILED) {
                previous.copy(
                    active = false,
                    paused = false,
                    stopping = false,
                    phase = SetPhase.IDLE,
                    remainingMs = 0L,
                    stopReason = reason,
                )
            } else SetRunState(stopReason = reason)
        }
    }

    private companion object {
        const val TICK_MS = 100L
        const val MIN_INTERVAL_MS = 100L
    }
}
