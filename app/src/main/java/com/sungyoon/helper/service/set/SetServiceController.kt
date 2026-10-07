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
import kotlinx.coroutines.CompletableDeferred
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
    private val itemsReady = CompletableDeferred<Unit>()
    private val messages = SetMessageOverlayController(context)
    private val runner = SetRunner(scope, items, options, SystemClock::elapsedRealtime, execute)
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
        itemCollectionJob = collectItems()
        scope.launch {
            var previousReason: SetStopReason? = null
            runner.state.collect { state ->
                if (disposed) return@collect
                publish(state)
                if (state.stopReason != previousReason) {
                    SetProgressFormatter.error(context, state)?.let(::showMessage)
                }
                previousReason = state.stopReason
            }
        }
    }

    private fun collectItems(): Job = scope.launch {
        SetStore.itemsFlow(context).collect { latest ->
            if (disposed) return@collect
            items.value = latest
            itemsReady.complete(Unit)
        }
    }.also { job ->
        job.invokeOnCompletion { cause ->
            if (cause != null && !itemsReady.isCompleted) itemsReady.completeExceptionally(cause)
        }
    }

    private suspend fun refreshItemsForStart() {
        try {
            // A completed edit can precede its flowOn(IO) delivery. Retire queued old emissions first.
            itemCollectionJob?.cancelAndJoin()
            items.value = SetStore.itemsFlow(context).first()
        } finally {
            if (!disposed && scope.isActive) {
                // A cancelled startup still needs normal live updates for the next attempt.
                itemCollectionJob = collectItems()
            }
        }
    }

    fun start() {
        if (disposed || isTransitioning || SetRuntime.active) return
        starting = true
        pauseRequested = false
        // The manager must see ownership before asynchronous loading, so opening it can queue a pause.
        publish(SetRunState(active = true))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitConfiguration()
                itemsReady.await()
                refreshItemsForStart()
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

    fun pause() {
        if (disposed) return
        pauseRequested = true
        if (starting) return
        runner.pause()
        publish(runner.state.value)
    }

    fun resume() {
        if (disposed || stopping || managerVisible()) return
        pauseRequested = false
        if (starting) return
        runner.resume()
        publish(runner.state.value)
    }

    fun cancel(interrupted: Boolean = false) {
        if (disposed || stopping) return
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
                runner.stopAndJoin()
                if (disposed) return@launch
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
        SetRuntime.update(state)
        onRuntimeChanged()
    }

    private fun showMessage(message: String) {
        if (!disposed) messages.showMessage(message)
    }

    private companion object { const val TAG = "SetServiceController" }
}
