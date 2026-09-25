package com.sungyoon.helper.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldGestureRunnerTest {
    @Test
    fun chunksContinueTheSamePointerUntilFinalRelease() = runBlocking {
        val backend = FakeBackend()
        val states = mutableListOf<Boolean>()
        val runner = HoldGestureRunner(backend, states::add)

        assertTrue(runner.start(120f, 340f))
        val first = backend.strokes.single()
        assertNull(first.previous)
        assertTrue(first.willContinue)
        assertEquals(100L, first.durationMs)
        backend.complete(0)
        val second = backend.strokes.last()
        assertSame(first, second.previous)
        assertEquals(120f, second.x, 0f)
        assertEquals(340f, second.y, 0f)
        assertTrue(second.willContinue)

        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { runner.stopAndAwait() }
        assertFalse(runner.isRunning)
        assertFalse(stopping.isCompleted)
        assertEquals(2, backend.strokes.size)
        backend.complete(1)
        val release = backend.strokes.last()
        assertSame(second, release.previous)
        assertFalse(release.willContinue)
        assertEquals(1L, release.durationMs)
        backend.complete(2)
        stopping.join()
        assertEquals(listOf(true, false), states)
        assertEquals(3, backend.strokes.size)
    }

    @Test
    fun rapidOffOnWaitsForReleaseAndLateCallbacksCannotReviveOldRun() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        assertTrue(runner.start(10f, 20f))
        runner.stop()
        assertFalse(runner.start(30f, 40f))
        backend.complete(0)
        assertFalse(runner.start(30f, 40f))
        backend.complete(1)

        assertTrue(runner.start(30f, 40f))
        assertNull(backend.strokes[2].previous)
        backend.complete(0)
        backend.cancel(1)
        assertTrue(runner.isRunning)
        assertEquals(3, backend.strokes.size)
        runner.stop()
        backend.complete(2)
        backend.complete(3)
        runner.stopAndAwait()
    }

    @Test
    fun cancelledGestureTurnsOffWithoutRestarting() {
        val backend = FakeBackend()
        val states = mutableListOf<Boolean>()
        var failures = 0
        val runner = HoldGestureRunner(backend, states::add, { failures++ })
        assertTrue(runner.start(10f, 20f))
        backend.cancel(0)
        backend.complete(0)
        assertFalse(runner.isRunning)
        assertEquals(1, backend.strokes.size)
        assertEquals(listOf(true, false), states)
        assertEquals(1, failures)
    }

    @Test
    fun rejectedContinuationTurnsOffAndIgnoresLateCompletion() {
        val backend = FakeBackend()
        var failures = 0
        val runner = HoldGestureRunner(backend, {}, { failures++ })
        assertTrue(runner.start(10f, 20f))
        backend.accept = false
        backend.complete(0)
        assertFalse(runner.isRunning)
        backend.complete(1)
        assertEquals(2, backend.strokes.size)
        assertEquals(1, failures)
    }

    @Test
    fun cancellingStopWaiterDoesNotAbandonRelease() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        assertTrue(runner.start(10f, 20f))
        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { runner.stopAndAwait() }
        stopping.cancelAndJoin()
        backend.complete(0)
        assertFalse(backend.strokes.last().willContinue)
        backend.complete(1)
        runner.stopAndAwait()
        assertTrue(runner.start(30f, 40f))
    }

    @Test
    fun serviceDisconnectCompletesWaitersWhenFrameworkDropsCallbacks() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        assertTrue(runner.start(10f, 20f))
        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { runner.stopAndAwait() }
        assertFalse(stopping.isCompleted)
        runner.onServiceDisconnected()
        stopping.join()
        assertFalse(runner.isRunning)
        backend.complete(0)
        assertEquals(1, backend.strokes.size)
    }

    @Test
    fun unsupportedOrInvalidInputNeverDispatches() {
        val backend = FakeBackend(supported = false)
        val runner = HoldGestureRunner(backend, {})
        assertFalse(runner.start(10f, 20f))
        assertTrue(backend.strokes.isEmpty())

        val supportedBackend = FakeBackend()
        val supportedRunner = HoldGestureRunner(supportedBackend, {})
        assertFalse(supportedRunner.start(Float.NaN, 20f))
        assertFalse(supportedRunner.start(-1f, 20f))
        assertTrue(supportedBackend.strokes.isEmpty())
    }

    private data class FakeStroke(
        val previous: HoldStroke?,
        val x: Float,
        val y: Float,
        val durationMs: Long,
        val willContinue: Boolean
    ) : HoldStroke

    private class FakeBackend(supported: Boolean = true) : HoldGestureBackend {
        override val isSupported = supported
        val strokes = mutableListOf<FakeStroke>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        var accept = true

        override fun createStroke(
            previous: HoldStroke?, x: Float, y: Float, durationMs: Long, willContinue: Boolean
        ): HoldStroke = FakeStroke(previous, x, y, durationMs, willContinue).also { strokes.add(it) }

        override fun dispatch(stroke: HoldStroke, onResult: (Boolean) -> Unit): Boolean {
            callbacks.add(onResult)
            return accept
        }

        fun complete(index: Int) = callbacks[index](true)
        fun cancel(index: Int) = callbacks[index](false)
    }
}
