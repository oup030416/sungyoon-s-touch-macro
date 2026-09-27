package com.sungyoon.helper.service

import com.sungyoon.helper.model.HighlightingPoint
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class HoldGestureRunnerTest {
    @Test
    fun joiningPendingHoldsWaitsForTheFirstResultAndReportsFailure() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        assertFalse(runner.awaitHoldsStarted())
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        val joining = async(start = CoroutineStart.UNDISPATCHED) { runner.awaitHoldsStarted() }
        assertFalse(starting.isCompleted)
        assertFalse(joining.isCompleted)
        backend.cancel(0)
        assertFalse(starting.await())
        assertFalse(joining.await())
        assertFalse(runner.awaitHoldsStarted())
        assertFalse(runner.isRunning)
        assertEquals(1, backend.batches.size)
    }

    @Test
    fun multipleHoldsAndTapShareOneStreamWithoutReplacingHoldContacts() = runBlocking {
        val backend = FakeBackend()
        val states = mutableListOf<Boolean>()
        val runner = HoldGestureRunner(backend, states::add)
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        assertFalse(starting.isCompleted)
        assertEquals(2, backend.batches[0].size)
        backend.complete(0)
        assertTrue(starting.await())
        val tap = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(700f, 800f) }
        assertEquals(2, backend.batches.size)
        backend.complete(1)
        val combined = backend.batches[2]
        assertEquals(3, combined.size)
        combined.take(2).forEachIndexed { index, stroke ->
            assertSame(backend.batches[1][index], stroke.previous)
            assertTrue(stroke.willContinue)
            assertEquals(51L, stroke.durationMs)
            assertEquals(0L, stroke.startTimeMs)
        }
        assertEquals(Kind.ACTION, combined.last().kind)
        assertNull(combined.last().previous)
        assertFalse(combined.last().willContinue)
        assertEquals(1L, combined.last().startTimeMs)
        backend.complete(2)
        assertTrue(tap.await())
        assertEquals(2, backend.batches[3].size)
        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { runner.stopAndAwait() }
        backend.complete(3)
        assertTrue(backend.batches[4].all { !it.willContinue && it.durationMs == 1L })
        backend.complete(4)
        stopping.join()
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun holdsCanJoinDragAndStopWithoutEndingTheOrdinaryContact() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        val dragging = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(100f, 300f, 600f, 300f, 500L) }
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        assertEquals(1, backend.batches[0].size)
        backend.complete(0)
        assertFalse(starting.isCompleted)
        assertEquals(3, backend.batches[1].size)
        assertSame(backend.batches[0].last(), backend.batches[1].first().previous)
        assertTrue(backend.batches[1].drop(1).all { it.startTimeMs == 1L && it.durationMs == 99L })
        assertEquals(0L, backend.batches[1].first().startTimeMs)
        backend.complete(1)
        assertTrue(starting.await())
        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { runner.stopAndAwait() }
        assertFalse(runner.isRunning)
        backend.complete(2)
        val releasing = backend.batches[3]
        assertTrue(releasing.take(2).all { !it.willContinue && it.durationMs == 1L })
        assertTrue(releasing.last().willContinue)
        assertEquals(500f, releasing.last().endX, 0f)
        backend.complete(3)
        stopping.join()
        assertFalse(dragging.isCompleted)
        assertEquals(1, backend.batches[4].size)
        assertSame(releasing.last(), backend.batches[4].last().previous)
        assertEquals(600f, backend.batches[4].last().endX, 0f)
        backend.complete(4)
        assertTrue(dragging.await())
        assertEquals(5, backend.batches.size)
    }

    @Test
    fun actionCancellationReleasesOnlyItsContactBeforeTheNextQueuedAction() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        assertTrue(runner.start(10f, 20f))
        val first = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(100f, 200f, 900f, 200f, 800L) }
        backend.complete(0)
        val previousAction = backend.batches[1].last()
        first.cancelAndJoin()
        val next = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(400f, 500f) }
        assertFalse(next.isCompleted)
        backend.complete(1)
        val release = backend.batches[2].last()
        assertSame(previousAction, release.previous)
        assertFalse(release.willContinue)
        assertEquals(previousAction.endX, release.endX, 0f)
        assertEquals(1L, release.durationMs)
        assertTrue(backend.batches[2].first().willContinue)
        backend.complete(2)
        yield()
        // The hold can already have entered another chunk before the next caller resumes.
        backend.complete(3)
        val nextContact = backend.batches[4].last()
        assertEquals(Kind.ACTION, nextContact.kind)
        assertNull(nextContact.previous)
        backend.complete(4)
        assertTrue(next.await())
        assertTrue(runner.isRunning)
        runner.onServiceDisconnected()
    }

    @Test
    fun cancelActionReturnsFalseAndKeepsHoldsRunning() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        assertTrue(runner.start(10f, 20f))
        val dragging = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(100f, 300f, 500f, 300f, 400L) }
        backend.complete(0)
        runner.cancelAction()
        backend.complete(1)
        assertFalse(backend.batches[2].last().willContinue)
        backend.complete(2)
        assertFalse(dragging.await())
        assertTrue(runner.isRunning)
        assertEquals(1, backend.batches.last().size)
        runner.onServiceDisconnected()
    }

    @Test
    fun cancellingFirstHoldChunkReleasesItWhileTheDragContinues() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        val dragging = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(100f, 300f, 500f, 300f, 400L) }
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        backend.complete(0)
        starting.cancelAndJoin()
        assertFalse(runner.isRunning)
        backend.complete(1)
        assertTrue(backend.batches[2].take(2).all { !it.willContinue })
        assertTrue(backend.batches[2].last().willContinue)
        backend.complete(2)
        assertEquals(1, backend.batches[3].size)
        backend.complete(3)
        assertTrue(dragging.await())
        runner.stopAndAwait()
    }

    @Test
    fun cancellingQueuedHoldStartDoesNotAddAnyHoldContact() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        val tapping = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(400f, 500f) }
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        starting.cancelAndJoin()
        assertFalse(runner.isRunning)
        backend.complete(0)
        assertTrue(tapping.await())
        assertEquals(1, backend.batches.size)
        assertEquals(Kind.ACTION, backend.batches.single().single().kind)
    }

    @Test
    fun physicalCancellationDropsAllContactsAndDoesNotRestartFromLateCallbacks() = runBlocking {
        val backend = FakeBackend()
        var failures = 0
        val runner = HoldGestureRunner(backend, {}, { failures++ })
        assertTrue(runner.start(10f, 20f))
        val dragging = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(100f, 300f, 900f, 300f, 800L) }
        backend.complete(0)
        backend.cancel(1)
        assertFalse(dragging.await())
        assertFalse(runner.isRunning)
        assertEquals(1, failures)
        assertEquals(2, backend.batches.size)
        backend.complete(1)
        assertEquals(2, backend.batches.size)
        assertTrue(runner.start(30f, 40f))
        backend.cancel(0)
        backend.complete(1)
        assertTrue(runner.isRunning)
        assertEquals(3, backend.batches.size)
        runner.onServiceDisconnected()
    }

    @Test
    fun physicalInputReleasesBothContactSetsBeforeTheNextOrdinaryAction() = runBlocking {
        val backend = FakeBackend()
        var failures = 0
        val runner = HoldGestureRunner(backend, {}, { failures++ })
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        backend.complete(0)
        assertTrue(starting.await())
        val dragging = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(100f, 300f, 900f, 300f, 800L) }
        backend.complete(1)
        runner.cancelFromPhysicalInput()
        assertFalse(runner.isRunning)
        val next = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(500f, 600f) }
        assertFalse(dragging.isCompleted)
        assertFalse(next.isCompleted)
        backend.complete(2)
        val releasing = backend.batches[3]
        assertEquals(3, releasing.size)
        releasing.forEachIndexed { index, stroke ->
            assertSame(backend.batches[2][index], stroke.previous)
            assertFalse(stroke.willContinue)
            assertEquals(1L, stroke.durationMs)
        }
        backend.complete(3)
        assertFalse(dragging.await())
        yield()
        assertEquals(1, backend.batches[4].size)
        assertEquals(Kind.ACTION, backend.batches[4].single().kind)
        assertNull(backend.batches[4].single().previous)
        backend.cancel(2)
        backend.complete(3)
        assertFalse(runner.isRunning)
        assertEquals(5, backend.batches.size)
        backend.complete(4)
        assertTrue(next.await())
        assertEquals(0, failures)
    }

    @Test
    fun actionRequestedAfterPhysicalInputStartsAfterTheHoldRelease() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        assertTrue(runner.start(100f, 200f))
        runner.cancelFromPhysicalInput()
        val next = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(500f, 600f) }
        assertEquals(1, backend.batches.size)
        assertFalse(next.isCompleted)
        backend.complete(0)
        val releaseAndAction = backend.batches[1]
        assertEquals(2, releaseAndAction.size)
        assertEquals(Kind.HOLD, releaseAndAction.first().kind)
        assertFalse(releaseAndAction.first().willContinue)
        assertEquals(1L, releaseAndAction.first().durationMs)
        assertEquals(Kind.ACTION, releaseAndAction.last().kind)
        assertNull(releaseAndAction.last().previous)
        assertEquals(2L, releaseAndAction.last().startTimeMs)
        backend.complete(1)
        assertTrue(next.await())
        assertFalse(runner.isRunning)
    }

    @Test
    fun freshOrdinaryContactStartsAfterTheLastHoldReleaseTimestamp() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        backend.complete(0)
        assertTrue(starting.await())
        runner.stop()
        val next = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(500f, 600f) }
        backend.complete(1)
        val releaseAndTap = backend.batches[2]
        assertTrue(releaseAndTap.take(2).all {
            it.previous != null && !it.willContinue && it.startTimeMs == 0L && it.durationMs == 1L
        })
        assertEquals(2L, releaseAndTap.last().startTimeMs)
        assertNull(releaseAndTap.last().previous)
        backend.complete(2)
        assertTrue(next.await())
        runner.stopAndAwait()
    }

    @Test
    fun newHoldsStartAfterTheOrdinaryReleaseAndFollowTheOldContactInTheBatch() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        val dragging = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(100f, 300f, 900f, 300f, 800L) }
        runner.cancelAction()
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        backend.complete(0)
        val releaseAndHolds = backend.batches[1]
        assertEquals(Kind.ACTION, releaseAndHolds.first().kind)
        assertSame(backend.batches[0].single(), releaseAndHolds.first().previous)
        assertEquals(0L, releaseAndHolds.first().startTimeMs)
        assertEquals(1L, releaseAndHolds.first().durationMs)
        assertFalse(releaseAndHolds.first().willContinue)
        assertTrue(releaseAndHolds.drop(1).all {
            it.previous == null && it.startTimeMs == 2L && it.durationMs > 0 && it.willContinue
        })
        backend.complete(1)
        assertFalse(dragging.await())
        assertTrue(starting.await())
        runner.onServiceDisconnected()
    }

    @Test
    fun rejectedCombinedDispatchCompletesBothCallersWithoutAutomaticRetry() = runBlocking {
        val backend = FakeBackend()
        var failures = 0
        val runner = HoldGestureRunner(backend, {}, { failures++ })
        val tapping = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(400f, 500f) }
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        backend.accept = false
        backend.complete(0)
        assertTrue(tapping.await())
        assertFalse(starting.await())
        assertFalse(runner.isRunning)
        assertEquals(1, failures)
        assertEquals(2, backend.batches.size)
        backend.complete(1)
        assertEquals(2, backend.batches.size)
    }

    @Test
    fun rejectedOrdinaryActionDoesNotReportAHoldFailure() = runBlocking {
        val backend = FakeBackend().apply { accept = false }
        var failures = 0
        val runner = HoldGestureRunner(backend, {}, { failures++ })
        assertFalse(runner.tap(40f, 50f))
        assertEquals(0, failures)
        assertEquals(1, backend.batches.size)
    }

    @Test
    fun rapidOffOnAndCancelledStopWaiterStillWaitForTheOldRelease() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        assertTrue(runner.start(10f, 20f))
        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { runner.stopAndAwait() }
        stopping.cancelAndJoin()
        assertFalse(runner.start(30f, 40f))
        backend.complete(0)
        assertFalse(runner.start(30f, 40f))
        backend.complete(1)
        runner.stopAndAwait()
        assertTrue(runner.start(30f, 40f))
        assertNull(backend.batches[2].single().previous)
        backend.complete(0)
        backend.cancel(1)
        assertTrue(runner.isRunning)
        assertEquals(3, backend.batches.size)
        runner.onServiceDisconnected()
    }

    @Test
    fun disconnectCompletesPendingWaitersWhenFrameworkDiscardsCallbacks() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(2)) }
        val tapping = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(300f, 400f) }
        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { runner.stopAllAndAwait() }
        runner.onServiceDisconnected()
        assertFalse(starting.await())
        assertFalse(tapping.await())
        stopping.join()
        backend.complete(0)
        assertFalse(runner.isRunning)
        assertFalse(runner.start(10f, 20f))
        assertFalse(runner.tap(10f, 20f))
        assertEquals(1, backend.batches.size)
    }

    @Test
    fun androidSevenStillUsesFiniteOrdinaryStrokesAndSerializesCancelledActions() = runBlocking {
        val backend = FakeBackend(supported = false)
        val runner = HoldGestureRunner(backend, {})
        assertFalse(runner.startHolds(points(1)))
        val dragging = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(10f, 20f, 900f, 20f, 800L) }
        assertEquals(800L, backend.batches[0].single().durationMs)
        assertFalse(backend.batches[0].single().willContinue)
        dragging.cancelAndJoin()
        val tapping = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(300f, 400f) }
        assertEquals(1, backend.batches.size)
        backend.complete(0)
        yield()
        assertEquals(2, backend.batches.size)
        assertNull(backend.batches[1].single().previous)
        assertFalse(backend.batches[1].single().willContinue)
        backend.complete(1)
        assertTrue(tapping.await())
        assertFalse(runner.tap(10f, 20f, 60_001L))
    }

    @Test
    fun verySlowOrdinaryDragExtendsChunksToTheNextPixelWithoutJitter() = runBlocking {
        val backend = FakeBackend()
        val runner = HoldGestureRunner(backend, {})
        val dragging = async(start = CoroutineStart.UNDISPATCHED) { runner.drag(100f, 200f, 103f, 200f, 10_000L) }
        var previousX = 100f
        var duration = 0L
        var completed = 0
        while (completed < backend.batches.size) {
            val stroke = backend.batches[completed].single()
            assertEquals(200f, stroke.endY, 0f)
            assertTrue(stroke.endX in previousX..103f)
            if (stroke.willContinue) assertTrue(stroke.endX > previousX)
            duration += stroke.durationMs
            previousX = stroke.endX
            backend.complete(completed++)
        }
        assertTrue(dragging.await())
        assertEquals(10_000L, duration)
        assertEquals(103f, previousX, 0f)
        assertEquals(4, completed)
    }

    @Test
    fun holdLimitReservesOneContactAndInvalidRequestsNeverDispatch() = runBlocking {
        val backend = FakeBackend(strokeLimit = 4)
        val runner = HoldGestureRunner(backend, {})
        assertEquals(3, runner.maxHoldCount)
        assertFalse(runner.startHolds(emptyList()))
        assertFalse(runner.startHolds(points(4)))
        val samePoint = points(1).single()
        assertFalse(runner.startHolds(listOf(samePoint, samePoint)))
        assertFalse(runner.start(Float.NaN, 20f))
        assertFalse(runner.start(-1f, 20f))
        assertFalse(runner.drag(0f, 0f, Float.POSITIVE_INFINITY, 0f, 100L))
        assertTrue(backend.batches.isEmpty())
        val starting = async(start = CoroutineStart.UNDISPATCHED) { runner.startHolds(points(3)) }
        backend.complete(0)
        assertTrue(starting.await())
        val tapping = async(start = CoroutineStart.UNDISPATCHED) { runner.tap(900f, 900f) }
        backend.complete(1)
        assertEquals(4, backend.batches[2].size)
        backend.complete(2)
        assertTrue(tapping.await())
        runner.onServiceDisconnected()
    }

    private fun points(count: Int): List<HighlightingPoint> = List(count) { index ->
        HighlightingPoint(id = "hold-$index", x = 100f + index * 50f, y = 200f, index = index, delayMs = 0L)
    }

    private enum class Kind { HOLD, ACTION }
    private data class FakeStroke(
        val kind: Kind,
        val previous: HoldStroke?,
        override val endX: Float,
        override val endY: Float,
        val durationMs: Long,
        val willContinue: Boolean,
        val startTimeMs: Long
    ) : HoldStroke

    private class FakeBackend(supported: Boolean = true, strokeLimit: Int = 10) : HoldGestureBackend {
        override val isSupported = supported
        override val maxStrokeCount = strokeLimit
        override val maxGestureDurationMs = 60_000L
        val batches = mutableListOf<List<FakeStroke>>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        var accept = true

        override fun createStroke(
            previous: HoldStroke?, x: Float, y: Float, durationMs: Long, willContinue: Boolean, startTimeMs: Long
        ): HoldStroke = FakeStroke(Kind.HOLD, previous, x, y, durationMs, willContinue, startTimeMs)

        override fun createActionStroke(
            previous: HoldStroke?, fromX: Float, fromY: Float, toX: Float, toY: Float,
            durationMs: Long, willContinue: Boolean, startTimeMs: Long
        ): HoldStroke = FakeStroke(
            Kind.ACTION, previous, toX.roundToInt().toFloat(), toY.roundToInt().toFloat(), durationMs, willContinue, startTimeMs
        )

        override fun dispatch(strokes: List<HoldStroke>, onResult: (Boolean) -> Unit): Boolean {
            batches.add(strokes.map { it as FakeStroke })
            callbacks.add(onResult)
            return accept
        }

        fun complete(index: Int) = callbacks[index](true)
        fun cancel(index: Int) = callbacks[index](false)
    }
}
