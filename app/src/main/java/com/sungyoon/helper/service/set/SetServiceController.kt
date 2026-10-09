package com.sungyoon.helper.service.set

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sungyoon.helper.R
import com.sungyoon.helper.data.SetStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.overlay.set.SetProgressFormatter
import com.sungyoon.helper.overlay.set.SetMessageOverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Connects persistent set items, the runner and presentation to one accessibility-service lifetime. */
class SetServiceController(
    private val context: Context,
    parentScope: CoroutineScope,
    options: () -> SetGestureOptions,
    private val awaitConfiguration: suspend () -> Unit,
    private val settleOrdinaryWork: suspend () -> Unit,
    execute: suspend (HighlightingPoint, String) -> Boolean,
    private val onRuntimeChanged: () -> Unit,
    private val managerVisible: () -> Boolean,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val items = MutableStateFlow<List<SetItem>>(emptyList())
    private val repeat = MutableStateFlow(false)
    private var ownerId: String? = null
    private var controlGeneration = 0L
    private var resumeJob: Job? = null
    private val messages = SetMessageOverlayController(context)
    private val runner = SetRunner(scope, items, options, SystemClock::elapsedRealtime, execute, repeatEnabled = { repeat.value })
    private var itemCollectionJob: Job? = null
    private var startJob: Job? = null
    private var stopping = false
    private var starting = false
    private var pauseRequested = false
    private var disposed = false

    val isTransitioning: Boolean get() = starting || stopping
    val blocksOrdinaryWork: Boolean get() = isTransitioning || SetRuntime.active
    val isGesturing: Boolean get() = runner.state.value.let {
        it.active && !it.paused && !it.stopping && (it.phase == SetPhase.TOUCH || it.phase == SetPhase.RUN)
    }

    init {
        scope.launch {
            var previousReason: SetStopReason? = null
            runner.state.collect { state ->
                if (disposed || (starting && !state.active) || stopping) return@collect
                publish(state)
                if (state.stopReason != previousReason) {
                    SetProgressFormatter.error(context, state)?.let(::showMessage)
                }
                previousReason = state.stopReason
            }
        }
    }

    private fun collectItems(setId: String): Job = scope.launch {
        try {
            SetStore.setFlow(context, setId).collect { latest ->
                if (disposed || ownerId != setId) return@collect
                if (latest == null) { cancel(interrupted = true, setId = setId); return@collect }
                items.value = latest.items
                repeat.value = latest.repeatEnabled
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Log.e(TAG, "Owned set collection failed", failure)
            cancel(interrupted = true, setId = setId)
        }
    }

    private suspend fun refreshItems(setId: String): Boolean {
        // Retire queued old IO emissions before reading the just-flushed definition.
        itemCollectionJob?.cancelAndJoin()
        itemCollectionJob = null
        val latest = SetStore.setFlow(context, setId).first() ?: return false
        if (disposed || ownerId != setId) return false
        items.value = latest.items
        repeat.value = latest.repeatEnabled
        itemCollectionJob = collectItems(setId)
        return true
    }

    fun start(setId: String?) {
        if (setId.isNullOrBlank() || disposed || isTransitioning || SetRuntime.active) return
        ownerId = setId
        controlGeneration++
        starting = true
        pauseRequested = false
        // The manager must see ownership before asynchronous loading, so opening it can queue a pause.
        publish(SetRunState(active = true))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitConfiguration()
                if (!refreshItems(setId)) { showMessage(context.getString(R.string.set_missing_message)); return@launch }
                currentCoroutineContext().ensureActive()
                if (items.value.none { it.isExecutable }) {
                    showMessage(context.getString(R.string.set_empty_message))
                    return@launch
                }
                // Ordinary gestures and pending store writes must finish before the new run starts.
                settleOrdinaryWork()
                currentCoroutineContext().ensureActive()
                if (disposed) return@launch
                if (!runner.start(paused = pauseRequested || managerVisible())) {
                    showMessage(context.getString(R.string.set_empty_message))
                    return@launch
                }
                publish(runner.state.value)
                if (runner.state.value.active) showMessage(context.getString(R.string.set_started_message))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Set startup failed", failure)
                showMessage(context.getString(R.string.set_failed_message, context.getString(R.string.set_title)))
            } finally {
                starting = false
                if (!disposed && !stopping && !runner.state.value.active) publish(runner.state.value)
            }
        }
        startJob = job
        job.start()
    }

    fun pause(setId: String? = null) {
        if (disposed || (setId != null && ownerId != setId)) return
        controlGeneration++
        resumeJob?.cancel()
        pauseRequested = true
        if (starting) return
        runner.pause()
        publish(runner.state.value)
    }

    fun resume(setId: String? = null) {
        val owner = ownerId ?: return
        if (disposed || stopping || managerVisible() || (setId != null && owner != setId)) return
        if (starting) { pauseRequested = false; return }
        val generation = ++controlGeneration
        resumeJob?.cancel()
        resumeJob = scope.launch {
            try {
                awaitConfiguration()
                if (!refreshItems(owner)) { cancel(interrupted = true, setId = owner); return@launch }
                currentCoroutineContext().ensureActive()
                if (controlGeneration != generation || ownerId != owner || stopping || managerVisible()) return@launch
                pauseRequested = false
                runner.resume()
                publish(runner.state.value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.e(TAG, "Set resume failed", failure)
                cancel(interrupted = true, setId = owner)
            }
        }
    }

    fun cancel(interrupted: Boolean = false, setId: String? = null) {
        if (disposed || stopping || (setId != null && ownerId != setId)) return
        controlGeneration++
        val resuming = resumeJob
        resuming?.cancel()
        val wasActive = starting || SetRuntime.active
        val startup = startJob
        stopping = true
        pauseRequested = false
        if (wasActive) publish(SetRuntime.state.value.copy(stopping = true))
        startup?.cancel()
        runner.cancel()
        scope.launch {
            try {
                startup?.join()
                resuming?.join()
                runner.stopAndJoin()
                if (disposed) return@launch
                stopping = false
                publish(SetRunState(stopReason = if (interrupted) SetStopReason.INTERRUPTED else SetStopReason.CANCELLED))
                if (interrupted && wasActive) showMessage(context.getString(R.string.set_interrupted_message))
            } finally {
                stopping = false
            }
        }
    }

    fun rejectOrdinaryStart(): Boolean {
        if (!blocksOrdinaryWork) return false
        showMessage(context.getString(R.string.set_blocked_message))
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        runner.dispose()
        scope.cancel()
        messages.dispose()
        SetRuntime.update(SetRunState())
    }

    private fun publish(state: SetRunState) {
        if (disposed) return
        SetRuntime.update(state.copy(setId = if (state.active) ownerId else null))
        if (!state.active && !starting && !stopping) {
            itemCollectionJob?.cancel()
            itemCollectionJob = null
            ownerId = null
        }
        onRuntimeChanged()
    }

    private fun showMessage(message: String) {
        if (!disposed) messages.showMessage(message)
    }

    private companion object { const val TAG = "SetServiceController" }
}
