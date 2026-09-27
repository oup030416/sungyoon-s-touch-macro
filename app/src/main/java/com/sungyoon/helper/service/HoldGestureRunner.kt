package com.sungyoon.helper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import com.sungyoon.helper.model.HighlightingPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.roundToInt

/** One dispatch owner for continued holds and one ordinary contact. Call only on the main thread. */
@MainThread
class HoldGestureRunner internal constructor(
    private val backend: HoldGestureBackend,
    private val onStateChanged: (Boolean) -> Unit,
    private val onFailure: () -> Unit = {}
) {
    constructor(
        service: AccessibilityService,
        onStateChanged: (Boolean) -> Unit,
        onFailure: () -> Unit = {}
    ) : this(AndroidHoldGestureBackend(service), onStateChanged, onFailure)

    val isSupported: Boolean get() = backend.isSupported
    val maxHoldCount: Int get() = min(9, backend.maxStrokeCount - 1).coerceAtLeast(0)
    var isRunning: Boolean = false
        private set

    private data class Location(val x: Float, val y: Float)
    private class Holds(val locations: List<Location>) {
        var strokes: List<HoldStroke?> = List(locations.size) { null }
        var releaseRequested = false
        val started = CompletableDeferred<Boolean>()
        val released = CompletableDeferred<Unit>()
    }
    private class Action(
        val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val durationMs: Long
    ) {
        var stroke: HoldStroke? = null
        var elapsedMs = 0L
        var releaseRequested = false
        val result = CompletableDeferred<Boolean>()
        val released = CompletableDeferred<Unit>()
    }
    private class Dispatch(
        val generation: Long,
        val number: Long,
        val strokes: List<HoldStroke>,
        val holds: Holds?,
        val releasesHolds: Boolean,
        val action: Action?,
        val finishesAction: Boolean,
        val actionElapsedMs: Long
    )

    private val actionMutex = Mutex()
    private var holds: Holds? = null
    private var action: Action? = null
    private var inFlight: Dispatch? = null
    private var generation = 0L
    private var nextDispatchNumber = 0L
    private var pumping = false
    private var disconnected = false

    /** The caller owns this start request; cancellation releases any accepted first chunk. */
    suspend fun startHolds(points: List<HighlightingPoint>): Boolean {
        currentCoroutineContext().ensureActive()
        if (points.map { it.id }.distinct().size != points.size) return false
        val request = beginHolds(points.map { Location(it.x, it.y) }) ?: return false
        return try {
            request.started.await()
        } catch (cancelled: CancellationException) {
            releaseHolds(request)
            throw cancelled
        }
    }

    /** Wait for an existing request without taking ownership of its cancellation. */
    suspend fun awaitHoldsStarted(): Boolean {
        val request = holds ?: return false
        return request.started.await()
    }

    /** Compatibility entry point; returns acceptance without waiting for the first chunk. */
    fun start(x: Float, y: Float): Boolean = beginHolds(listOf(Location(x, y))) != null

    private fun beginHolds(locations: List<Location>): Holds? {
        if (disconnected || !isSupported || holds != null || locations.size !in 1..maxHoldCount ||
            locations.any { !validCoordinate(it.x, it.y) }
        ) return null
        val request = Holds(locations)
        holds = request
        setRunning(true)
        pump()
        return request.takeIf { holds === request }
    }

    /** Release only the hold contacts; an ordinary tap or drag keeps its stroke identity. */
    fun stop() {
        holds?.let { releaseHolds(it) }
    }

    suspend fun stopAndAwait() {
        val request = holds ?: return
        releaseHolds(request)
        request.released.await()
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 50L): Boolean =
        performAction(x, y, x, y, durationMs)

    suspend fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean =
        performAction(fromX, fromY, toX, toY, durationMs)

    private suspend fun performAction(
        fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long
    ): Boolean = actionMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!validCoordinate(fromX, fromY) || !validCoordinate(toX, toY) || durationMs <= 0L ||
            durationMs > backend.maxGestureDurationMs
        ) return@withLock false
        // A cancelled caller releases the mutex before its final continuation completes.
        // The next caller must wait for that contact to lift before adding a fresh stroke.
        action?.released?.await()
        currentCoroutineContext().ensureActive()
        if (disconnected) return@withLock false
        val request = Action(fromX, fromY, toX, toY, durationMs)
        action = request
        pump()
        try {
            request.result.await()
        } catch (cancelled: CancellationException) {
            releaseAction(request)
            throw cancelled
        }
    }

    fun cancelAction() {
        action?.let { releaseAction(it) }
    }

    /** Some Android versions cancel target input without cancelling gesture-result callbacks. */
    fun cancelFromPhysicalInput() {
        val oldHolds = holds
        val oldAction = action
        oldHolds?.let { releaseHolds(it, pumpNext = false) }
        oldAction?.let { releaseAction(it, pumpNext = false) }
        pump()
    }

    suspend fun stopAllAndAwait() {
        val oldHolds = holds
        val oldAction = action
        oldHolds?.let { releaseHolds(it, pumpNext = false) }
        oldAction?.let { releaseAction(it, pumpNext = false) }
        pump()
        oldHolds?.released?.await()
        oldAction?.released?.await()
    }

    /** The framework cancels input on disconnect and can discard its pending result callback. */
    fun onServiceDisconnected() {
        disconnected = true
        abortStream(notifyFailure = false)
    }

    private fun releaseHolds(request: Holds, pumpNext: Boolean = true) {
        if (holds !== request) return
        request.releaseRequested = true
        setRunning(false)
        request.started.complete(false)
        if (request.strokes.all { it == null }) {
            holds = null
            request.released.complete(Unit)
        }
        if (pumpNext) pump()
    }

    private fun releaseAction(request: Action, pumpNext: Boolean = true) {
        if (action !== request) return
        request.releaseRequested = true
        if (request.stroke == null) {
            action = null
            request.released.complete(Unit)
            request.result.complete(false)
        }
        if (pumpNext) pump()
    }

    private fun setRunning(value: Boolean) {
        if (isRunning == value) return
        isRunning = value
        onStateChanged(value)
    }

    private fun pump() {
        if (pumping || inFlight != null || disconnected) return
        pumping = true
        try {
            // The loop also supports a backend reporting a result synchronously.
            while (inFlight == null && !disconnected && (holds != null || action != null)) {
                val batch = try {
                    createDispatch()
                } catch (_: RuntimeException) {
                    abortStream(notifyFailure = true)
                    break
                }
                inFlight = batch
                val accepted = try {
                    backend.dispatch(batch.strokes) { completed -> onResult(batch, completed) }
                } catch (_: RuntimeException) {
                    false
                }
                if (!accepted && inFlight === batch) abortStream(notifyFailure = true)
            }
        } finally {
            pumping = false
        }
    }

    private fun createDispatch(): Dispatch {
        val currentHolds = holds
        val currentAction = action
        val releasesHolds = currentHolds?.releaseRequested == true
        val actionElapsed = when {
            currentAction == null -> 0L
            currentAction.releaseRequested -> currentAction.elapsedMs
            !isSupported -> currentAction.durationMs
            else -> nextActionElapsed(currentAction, currentHolds != null)
        }
        val duration = when {
            currentAction == null -> CHUNK_DURATION_MS
            currentAction.releaseRequested -> RELEASE_DURATION_MS
            else -> actionElapsed - currentAction.elapsedMs
        }
        val finishesAction = currentAction != null &&
            (currentAction.releaseRequested || actionElapsed == currentAction.durationMs)
        val hasContinuations = currentHolds?.strokes?.any { it != null } == true || currentAction?.stroke != null
        // The framework requires the first step to contain exactly the previous continued
        // contacts. New contacts must enter later, even when sharing this dispatch.
        val newHolds = currentHolds?.strokes?.all { it == null } == true
        val holdStart = if (hasContinuations && newHolds) {
            if (finishesAction && duration == RELEASE_DURATION_MS) 2L else 1L
        } else 0L
        val actionStart = if (hasContinuations && currentAction != null && currentAction.stroke == null) {
            if (releasesHolds) 2L else 1L
        } else 0L
        // An UP and new DOWN at the same timestamp retain the ending stroke in the framework's
        // point array. Its new pointer index can then exceed the surviving pointer count.
        val batchEnd = maxOf(actionStart + duration, holdStart + 1L)
        val holdStrokes = currentHolds?.locations?.mapIndexed { index, location ->
            backend.createStroke(
                previous = currentHolds.strokes[index],
                x = location.x,
                y = location.y,
                durationMs = if (releasesHolds) RELEASE_DURATION_MS else batchEnd - holdStart,
                willContinue = !releasesHolds,
                startTimeMs = holdStart
            )
        }.orEmpty()
        val actionStroke = currentAction?.let { request ->
            val progress = actionElapsed.toDouble() / request.durationMs.toDouble()
            val targetX = if (request.releaseRequested) request.stroke!!.endX
                else (request.fromX + (request.toX - request.fromX).toDouble() * progress).toFloat()
            val targetY = if (request.releaseRequested) request.stroke!!.endY
                else (request.fromY + (request.toY - request.fromY).toDouble() * progress).toFloat()
            backend.createActionStroke(
                previous = request.stroke,
                fromX = request.fromX,
                fromY = request.fromY,
                toX = targetX,
                toY = targetY,
                durationMs = duration,
                willContinue = !finishesAction,
                startTimeMs = actionStart
            )
        }
        // Publish tokens only after all paths were constructed successfully.
        currentHolds?.strokes = holdStrokes
        currentAction?.stroke = actionStroke
        // A new stroke must follow the existing contacts in each point array. In particular,
        // newly enabled holds join after the already-running ordinary contact.
        val strokes = if (holdStart > 0L && actionStroke != null) listOf(actionStroke) + holdStrokes
            else holdStrokes + listOfNotNull(actionStroke)
        return Dispatch(
            generation, ++nextDispatchNumber, strokes,
            currentHolds, releasesHolds, currentAction, finishesAction, actionElapsed
        )
    }

    private fun nextActionElapsed(request: Action, hasHolds: Boolean): Long {
        val next = request.elapsedMs + min(CHUNK_DURATION_MS, request.durationMs - request.elapsedMs)
        if (hasHolds || next == request.durationMs) return next
        val startX = (request.stroke?.endX ?: request.fromX).roundToInt()
        val startY = (request.stroke?.endY ?: request.fromY).roundToInt()
        fun movesAt(elapsed: Long): Boolean {
            val progress = elapsed.toDouble() / request.durationMs.toDouble()
            return (request.fromX + (request.toX - request.fromX).toDouble() * progress).roundToInt() != startX ||
                (request.fromY + (request.toY - request.fromY).toDouble() * progress).roundToInt() != startY
        }
        if (movesAt(next)) return next
        // Without another moving contact, rounded stationary continuations contain no events.
        // Extend only to the next pixel on the original path; never add jitter to a normal drag.
        // A new hold request must wait for this already-dispatched segment to finish.
        if (!movesAt(request.durationMs)) return request.durationMs
        var low = next + 1L
        var high = request.durationMs
        while (low < high) {
            val middle = low + (high - low) / 2L
            if (movesAt(middle)) high = middle else low = middle + 1L
        }
        return low
    }

    private fun onResult(batch: Dispatch, completed: Boolean) {
        if (inFlight !== batch || batch.generation != generation || batch.number != nextDispatchNumber) return
        inFlight = null
        if (!completed) {
            abortStream(notifyFailure = true)
            return
        }
        val currentHolds = batch.holds?.takeIf { holds === it }
        val currentAction = batch.action?.takeIf { action === it }
        // Update both contact sets before completing any deferred, which can resume a caller inline.
        if (currentHolds != null && batch.releasesHolds) holds = null
        if (currentAction != null) {
            currentAction.elapsedMs = batch.actionElapsedMs
            if (batch.finishesAction) action = null
        }
        if (currentHolds != null) {
            if (batch.releasesHolds) currentHolds.released.complete(Unit)
            else if (!currentHolds.releaseRequested) currentHolds.started.complete(true)
        }
        if (currentAction != null && batch.finishesAction) {
            currentAction.released.complete(Unit)
            currentAction.result.complete(!currentAction.releaseRequested)
        }
        pump()
    }

    private fun abortStream(notifyFailure: Boolean) {
        val oldHolds = holds
        val oldAction = action
        val failedHolds = oldHolds != null && !oldHolds.releaseRequested
        generation++
        inFlight = null
        holds = null
        action = null
        setRunning(false)
        oldHolds?.started?.complete(false)
        oldHolds?.released?.complete(Unit)
        oldAction?.released?.complete(Unit)
        oldAction?.result?.complete(false)
        if (notifyFailure && failedHolds) onFailure()
    }

    private fun validCoordinate(x: Float, y: Float): Boolean = x.isFinite() && y.isFinite() && x >= 0f && y >= 0f

    private companion object {
        const val CHUNK_DURATION_MS = 100L
        const val RELEASE_DURATION_MS = 1L
    }
}

internal interface HoldStroke {
    val endX: Float
    val endY: Float
}

/** Keeps lifecycle and cancellation tests independent of Android's input injector. */
internal interface HoldGestureBackend {
    val isSupported: Boolean
    val maxStrokeCount: Int
    val maxGestureDurationMs: Long
    fun createStroke(
        previous: HoldStroke?, x: Float, y: Float, durationMs: Long, willContinue: Boolean, startTimeMs: Long = 0L
    ): HoldStroke
    fun createActionStroke(
        previous: HoldStroke?, fromX: Float, fromY: Float, toX: Float, toY: Float,
        durationMs: Long, willContinue: Boolean, startTimeMs: Long = 0L
    ): HoldStroke
    fun dispatch(strokes: List<HoldStroke>, onResult: (Boolean) -> Unit): Boolean
}

private class AndroidHoldGestureBackend(private val service: AccessibilityService) : HoldGestureBackend {
    override val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    override val maxStrokeCount: Int get() = GestureDescription.getMaxStrokeCount()
    override val maxGestureDurationMs: Long get() = GestureDescription.getMaxGestureDuration()
    private var dispatchNumber = 0L

    private class AndroidStroke(
        val stroke: GestureDescription.StrokeDescription,
        override val endX: Float,
        override val endY: Float
    ) : HoldStroke

    override fun createStroke(
        previous: HoldStroke?, x: Float, y: Float, durationMs: Long, willContinue: Boolean, startTimeMs: Long
    ): HoldStroke {
        check(isSupported) { "Continued gestures require Android 8" }
        val anchorX = x.roundToInt().toFloat()
        val anchorY = y.roundToInt().toFloat()
        val prior = previous as? AndroidStroke
        val startX = prior?.endX ?: anchorX
        val startY = prior?.endY ?: anchorY
        // A stationary continuation produces no MotionEvents. Each endpoint must differ even
        // on Android 8-10, where a whole 100ms chunk can have only one sampled endpoint.
        val nextX = if (!willContinue || startX != anchorX) anchorX else inwardPixel(anchorX)
        return createAndroidStroke(prior, startX, startY, nextX, anchorY, durationMs, willContinue, startTimeMs)
    }

    override fun createActionStroke(
        previous: HoldStroke?, fromX: Float, fromY: Float, toX: Float, toY: Float,
        durationMs: Long, willContinue: Boolean, startTimeMs: Long
    ): HoldStroke {
        val prior = previous as? AndroidStroke
        val startX = prior?.endX ?: fromX.roundToInt().toFloat()
        val startY = prior?.endY ?: fromY.roundToInt().toFloat()
        val endX = toX.roundToInt().toFloat()
        val endY = toY.roundToInt().toFloat()
        return createAndroidStroke(prior, startX, startY, endX, endY, durationMs, willContinue, startTimeMs)
    }

    private fun createAndroidStroke(
        prior: AndroidStroke?, fromX: Float, fromY: Float, toX: Float, toY: Float,
        durationMs: Long, willContinue: Boolean, startTimeMs: Long
    ): AndroidStroke {
        val path = Path().apply {
            moveTo(fromX, fromY)
            if (fromX != toX || fromY != toY) lineTo(toX, toY)
        }
        val stroke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            prior?.stroke?.continueStroke(path, startTimeMs, durationMs, willContinue)
                ?: GestureDescription.StrokeDescription(path, startTimeMs, durationMs, willContinue)
        } else {
            check(prior == null && !willContinue)
            GestureDescription.StrokeDescription(path, startTimeMs, durationMs)
        }
        return AndroidStroke(stroke, toX, toY)
    }

    private fun inwardPixel(x: Float): Float = if (x >= 1f) x - 1f else x + 1f

    override fun dispatch(strokes: List<HoldStroke>, onResult: (Boolean) -> Unit): Boolean {
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke((it as AndroidStroke).stroke) }
        val gesture = builder.build()
        val number = ++dispatchNumber
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onResult(true)
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w("SungyoonHold", "Gesture cancelled: dispatch=$number, contacts=${strokes.size}")
                    onResult(false)
                }
            },
            null
        )
        if (!accepted) Log.w("SungyoonHold", "Gesture rejected: dispatch=$number, contacts=${strokes.size}")
        return accepted
    }
}
