package com.sungyoon.helper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.roundToInt

/** Owns a single continued gesture. All entry points and callbacks run on the main thread. */
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
    var isRunning: Boolean = false
        private set

    private var nextGeneration = 0L
    private var activeRun: Run? = null

    private class Run(val generation: Long, val x: Float, val y: Float) {
        var stroke: HoldStroke? = null
        var dispatchNumber = 0L
        var inFlight = false
        var releaseRequested = false
        val released = CompletableDeferred<Unit>()
    }

    /** A new run is accepted only after the previous run has released its pointer. */
    fun start(x: Float, y: Float): Boolean {
        if (!isSupported || activeRun != null || !x.isFinite() || !y.isFinite() || x < 0 || y < 0) {
            return false
        }
        val run = Run(++nextGeneration, x, y)
        activeRun = run
        isRunning = true
        dispatchNext(run)
        if (activeRun === run && isRunning) onStateChanged(true)
        return activeRun === run && isRunning
    }

    /** Request a final continuation; never inject a separate tap to release a hold. */
    fun stop() {
        val run = activeRun ?: return
        run.releaseRequested = true
        if (isRunning) {
            isRunning = false
            onStateChanged(false)
        }
        if (!run.inFlight) dispatchNext(run)
    }

    /** Cancellation of the waiter does not cancel the pending pointer release. */
    suspend fun stopAndAwait() {
        val run = activeRun ?: return
        stop()
        run.released.await()
    }

    /** The framework cancels input on disconnect and can discard the pending result callback. */
    fun onServiceDisconnected() {
        val run = activeRun ?: return
        nextGeneration++
        finish(run, failed = false)
    }

    private fun dispatchNext(run: Run) {
        if (activeRun !== run || run.generation != nextGeneration || run.inFlight) return
        val finalStroke = run.releaseRequested
        val stroke = try {
            backend.createStroke(
                previous = run.stroke,
                x = run.x,
                y = run.y,
                durationMs = if (finalStroke) RELEASE_DURATION_MS else CHUNK_DURATION_MS,
                willContinue = !finalStroke
            )
        } catch (_: RuntimeException) {
            finish(run, failed = true)
            return
        }
        run.stroke = stroke
        run.inFlight = true
        val dispatchNumber = ++run.dispatchNumber
        val accepted = try {
            backend.dispatch(stroke) { completed ->
                if (activeRun !== run || run.generation != nextGeneration ||
                    run.dispatchNumber != dispatchNumber || !run.inFlight
                ) return@dispatch
                run.inFlight = false
                when {
                    !completed -> finish(run, failed = !run.releaseRequested)
                    finalStroke -> finish(run, failed = false)
                    else -> dispatchNext(run)
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted && activeRun === run && run.dispatchNumber == dispatchNumber) {
            finish(run, failed = true)
        }
    }

    private fun finish(run: Run, failed: Boolean) {
        if (activeRun !== run) return
        activeRun = null
        run.inFlight = false
        if (isRunning) {
            isRunning = false
            onStateChanged(false)
        }
        if (failed) onFailure()
        run.released.complete(Unit)
    }

    private companion object {
        const val CHUNK_DURATION_MS = 100L
        const val RELEASE_DURATION_MS = 1L
    }
}

internal interface HoldStroke

/** The boundary lets cancellation and late callbacks be tested without injecting real touches. */
internal interface HoldGestureBackend {
    val isSupported: Boolean
    fun createStroke(previous: HoldStroke?, x: Float, y: Float, durationMs: Long, willContinue: Boolean): HoldStroke
    fun dispatch(stroke: HoldStroke, onResult: (Boolean) -> Unit): Boolean
}

private class AndroidHoldGestureBackend(private val service: AccessibilityService) : HoldGestureBackend {
    override val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @RequiresApi(Build.VERSION_CODES.O)
    private class AndroidStroke(
        val stroke: GestureDescription.StrokeDescription,
        val endX: Float,
        val endY: Float
    ) : HoldStroke

    override fun createStroke(
        previous: HoldStroke?,
        x: Float,
        y: Float,
        durationMs: Long,
        willContinue: Boolean
    ): HoldStroke {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) error("Continued gestures require Android 8")
        val anchorX = x.roundToInt().toFloat()
        val anchorY = y.roundToInt().toFloat()
        val prior = previous as? AndroidStroke
        val startX = prior?.endX ?: anchorX
        val startY = prior?.endY ?: anchorY
        // Android rejects a stationary continuation because it produces no MotionEvents.
        // Use one physical pixel, below touch slop, and keep each segment's endpoint distinct:
        // Android 8-10 sample at 100ms, so an out-and-back path in one chunk is also stationary.
        val nextX = if (!willContinue || startX != anchorX) anchorX
            else if (anchorX >= 1f) anchorX - 1f else anchorX + 1f
        val path = Path().apply {
            moveTo(startX, startY)
            if (startX != nextX || startY != anchorY) lineTo(nextX, anchorY)
        }
        val stroke = if (previous == null) {
            GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
        } else {
            prior!!.stroke.continueStroke(path, 0L, durationMs, willContinue)
        }
        return AndroidStroke(stroke, nextX, anchorY)
    }

    override fun dispatch(stroke: HoldStroke, onResult: (Boolean) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) error("Continued gestures require Android 8")
        val gesture = GestureDescription.Builder().addStroke((stroke as AndroidStroke).stroke).build()
        return service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onResult(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = onResult(false)
            },
            null
        )
    }
}
