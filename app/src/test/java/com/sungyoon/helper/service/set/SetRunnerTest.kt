package com.sungyoon.helper.service.set

import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.ReservationConfig
import com.sungyoon.helper.model.SetItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetRunnerTest {
    private data class Action(val id: String, val atMs: Long, val label: String, val durationMs: Long)

    private fun point(id: String, index: Int = 0, drag: Boolean = false) = HighlightingPoint(
        id = id,
        x = index.toFloat(),
        y = 10f,
        index = index,
        delayMs = 1000L,
        actionType = if (drag) HighlightingPoint.ACTION_TYPE_DRAG else HighlightingPoint.ACTION_TYPE_TAP,
        dragToX = if (drag) index + 100f else index.toFloat(),
    )

    private fun touch(id: String, vararg points: HighlightingPoint) =
        SetItem.Touch(id = id, name = id, points = points.toList())

    private fun wait(id: String, durationMs: Long = 100L) =
        SetItem.Wait(id = id, name = id, durationMs = durationMs)

    private fun TestScope.runner(
        items: MutableStateFlow<List<SetItem>>,
        actions: MutableList<Action> = mutableListOf(),
        options: () -> SetGestureOptions = { SetGestureOptions(intervalMs = 100L) },
        execute: (suspend (HighlightingPoint, String) -> Boolean)? = null,
        repeat: () -> Boolean = { true },
    ) = SetRunner(
        scope = this,
        items = items,
        options = options,
        nowMs = { testScheduler.currentTime },
        repeatEnabled = repeat,
        execute = execute ?: { point, label ->
            val duration = if (point.isEffectiveDrag) point.dragDurationMs else 50L
            actions += Action(point.id, testScheduler.currentTime, label, duration)
            delay(duration)
            true
        },
    )

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun elapsedTimeAccumulatesAcrossPassesExcludesPausesAndResetsOnRestart() = runTest {
        val items = MutableStateFlow<List<SetItem>>(listOf(wait("elapsed", 1000L)))
        val runner = runner(items)
        assertTrue(runner.start())
        runCurrent()
        advance(2500L)
        assertEquals(3L, runner.state.value.pass)
        assertEquals(2500L, runner.state.value.elapsedMs)
        runner.pause()
        advance(5000L)
        assertEquals(2500L, runner.state.value.elapsedMs)
        runner.resume()
        runCurrent()
        advance(1000L)
        assertEquals(3500L, runner.state.value.elapsedMs)
        runner.stopAndJoin()
        assertEquals(0L, runner.state.value.elapsedMs)
        assertTrue(runner.start())
        runCurrent()
        assertEquals(0L, runner.state.value.elapsedMs)
        runner.stopAndJoin()
    }

    @Test
    fun initiallyPausedElapsedTimeIgnoresStartupWaitAndCapturesUnfinishedGesture() = runTest {
        val completion = CompletableDeferred<Unit>()
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("gesture", point("first"))))
        val runner = runner(items, execute = { _, _ -> completion.await(); true })
        assertTrue(runner.start(paused = true))
        runCurrent()
        advance(5000L)
        assertEquals(0L, runner.state.value.elapsedMs)
        runner.resume()
        runCurrent()
        advance(1250L)
        runner.pause()
        assertFalse(runner.state.value.paused)
        assertEquals(1250L, runner.state.value.elapsedMs)
        advance(5000L)
        assertEquals(1250L, runner.state.value.elapsedMs)
        completion.complete(Unit)
        runCurrent()
        assertTrue(runner.state.value.paused)
        assertEquals(1250L, runner.state.value.elapsedMs)
        runner.stopAndJoin()
    }

    @Test
    fun normalTouchIncludesFinalIntervalThenWaitsAndRepeatsFromBeginning() = runTest {
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("a", point("p")), wait("w", 200L)))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions)
        assertTrue(runner.start())
        assertTrue(runner.state.value.active)
        assertFalse(runner.start())
        runCurrent()
        assertEquals(listOf("p"), actions.map { it.id })
        advance(149L)
        assertEquals(SetPhase.TOUCH, runner.state.value.phase)
        advance(1L)
        assertEquals(SetPhase.WAIT, runner.state.value.phase)
        advance(200L)
        assertEquals(listOf(0L, 350L), actions.map { it.atMs })
        assertEquals(2L, runner.state.value.pass)
        runner.stopAndJoin()
    }

    @Test
    fun reservationIncludesFinalRestAndContinuesPointerOffsetAcrossCycles() = runTest {
        val reservation = SetItem.Reserved(
            id = "r", name = "r", points = listOf(point("p0"), point("p1", 1), point("p2", 2)),
            reservation = ReservationConfig(1, 1, 2),
        )
        val items = MutableStateFlow<List<SetItem>>(listOf(reservation, wait("w", 1000L)))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions)
        runner.start()
        runCurrent()
        advance(1000L)
        assertEquals(SetPhase.REST, runner.state.value.phase)
        assertEquals(1, runner.state.value.pointerOffset)
        advance(1000L)
        assertEquals("p1", actions.last().id)
        assertEquals(2000L, actions.last().atMs)
        assertEquals(2, runner.state.value.cycleCurrent)
        advance(1000L)
        assertEquals(SetPhase.REST, runner.state.value.phase)
        assertEquals("r", runner.state.value.currentItem?.id)
        advance(999L)
        assertEquals(SetPhase.REST, runner.state.value.phase)
        advance(1L)
        assertEquals("w", runner.state.value.currentItem?.id)
        runner.stopAndJoin()
    }

    @Test
    fun dragThatCannotFitIsDeferredWithoutLosingItsPointerOffset() = runTest {
        val reserved = SetItem.Reserved(
            id = "r", name = "r", points = listOf(point("tap"), point("drag", 1, drag = true), point("last", 2)),
            reservation = ReservationConfig(1, 1, 2),
        )
        val items = MutableStateFlow<List<SetItem>>(listOf(reserved, wait("w")))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions, options = { SetGestureOptions(100L, 900L) })
        runner.start()
        runCurrent()
        advance(1000L)
        assertEquals(listOf("tap"), actions.map { it.id })
        assertEquals(1, runner.state.value.pointerOffset)
        advance(1000L)
        assertEquals("drag", actions.last().id)
        assertEquals(2000L, actions.last().atMs)
        advance(1000L)
        assertEquals(2, runner.state.value.pointerOffset)
        assertEquals(SetPhase.REST, runner.state.value.phase)
        runner.stopAndJoin()
    }

    @Test
    fun coincidentAndNegligibleDragsUseTapTimingInsteadOfSkippingTheRun() = runTest {
        val coincident = point("coincident", drag = true).copy(dragToX = 0f, dragToY = 10f)
        val negligible = point("negligible", 1, drag = true).copy(dragToX = 3f, dragToY = 12f)
        val reserved = SetItem.Reserved(
            id = "r", name = "r", points = listOf(coincident, negligible),
            reservation = ReservationConfig(1, 1, 1),
        )
        val items = MutableStateFlow<List<SetItem>>(listOf(reserved, wait("w")))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions, options = { SetGestureOptions(100L, 10_000L) })
        runner.start()
        runCurrent()
        assertEquals("coincident", actions.single().id)
        assertEquals(50L, actions.single().durationMs)
        advance(150L)
        assertEquals("negligible", actions.last().id)
        assertEquals(150L, actions.last().atMs)
        assertEquals(50L, actions.last().durationMs)
        runner.stopAndJoin()
    }

    @Test
    fun cancelWaitsForDispatchedGestureAndNextStartResetsOffset() = runTest {
        val completion = CompletableDeferred<Unit>()
        val ids = mutableListOf<String>()
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("a", point("first"), point("second", 1))))
        val runner = runner(items, execute = { point, _ -> ids += point.id; completion.await(); true })
        runner.start()
        runCurrent()
        runner.cancel()
        assertTrue(runner.state.value.active)
        assertTrue(runner.state.value.stopping)
        assertFalse(runner.start())
        completion.complete(Unit)
        runCurrent()
        assertFalse(runner.state.value.active)
        assertEquals(SetStopReason.CANCELLED, runner.state.value.stopReason)
        assertEquals(listOf("first"), ids)
        assertTrue(runner.start())
        runCurrent()
        assertEquals(listOf("first", "first"), ids)
        runner.stopAndJoin()
    }

    @Test
    fun pauseSettlesGestureAndRetainsNextOffsetAndInterval() = runTest {
        val completion = CompletableDeferred<Unit>()
        val ids = mutableListOf<String>()
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("a", point("first"), point("second", 1))))
        val runner = runner(items, execute = { point, _ -> ids += point.id; completion.await(); true })
        runner.start()
        runCurrent()
        runner.pause()
        assertFalse(runner.state.value.paused)
        completion.complete(Unit)
        runCurrent()
        assertTrue(runner.state.value.paused)
        assertEquals(1, runner.state.value.pointerOffset)
        assertEquals(100L, runner.state.value.remainingMs)
        advance(5000L)
        assertEquals(listOf("first"), ids)
        assertEquals(100L, runner.state.value.remainingMs)
        runner.resume()
        runCurrent()
        advance(99L)
        assertEquals(listOf("first"), ids)
        advance(1L)
        assertEquals(listOf("first", "second"), ids)
        runner.stopAndJoin()
    }

    @Test
    fun pauseFreezesTimeAndCurrentSettingsWhileUpcomingItemsStayLive() = runTest {
        val a = wait("a", 500L)
        val b = wait("b", 500L)
        val items = MutableStateFlow<List<SetItem>>(listOf(a, b))
        val runner = runner(items)
        runner.start()
        runCurrent()
        advance(200L)
        runner.pause()
        assertEquals(300L, runner.state.value.remainingMs)
        items.value = listOf(a.copy(durationMs = 1000L), b.copy(durationMs = 200L))
        runCurrent()
        advance(5000L)
        assertEquals(300L, runner.state.value.remainingMs)
        assertEquals(500L, (runner.state.value.currentItem as SetItem.Wait).durationMs)
        runner.resume()
        runCurrent()
        advance(300L)
        assertEquals("b", runner.state.value.currentItem?.id)
        assertEquals(200L, runner.state.value.phaseDurationMs)
        advance(200L)
        assertEquals("a", runner.state.value.currentItem?.id)
        assertEquals(1000L, runner.state.value.phaseDurationMs)
        runner.stopAndJoin()
    }

    @Test
    fun deletingCurrentDiscardsRemainingPointersAndUsesVacatedSlot() = runTest {
        val completion = CompletableDeferred<Unit>()
        val ids = mutableListOf<String>()
        val a = touch("a", point("discard-owner"), point("never", 1))
        val b = wait("b")
        val c = touch("c", point("next"))
        val items = MutableStateFlow<List<SetItem>>(listOf(a, b, c))
        val runner = runner(items, execute = { point, _ ->
            ids += point.id
            if (point.id == "discard-owner") completion.await()
            true
        })
        runner.start()
        runCurrent()
        items.value = listOf(b, c)
        runCurrent()
        completion.complete(Unit)
        runCurrent()
        assertEquals("b", runner.state.value.currentItem?.id)
        advance(100L)
        assertEquals(listOf("discard-owner", "next"), ids)
        runner.stopAndJoin()
    }

    @Test
    fun deletingPausedCurrentClearsPublishedWorkAndResumesFromVacatedSlot() = runTest {
        val a = wait("a", 500L)
        val b = wait("b", 300L)
        val c = wait("c", 300L)
        val items = MutableStateFlow<List<SetItem>>(listOf(a, b, c))
        val runner = runner(items)
        runner.start()
        runCurrent()
        advance(200L)
        runner.pause()
        items.value = listOf(b, c)
        runCurrent()
        assertTrue(runner.state.value.active)
        assertTrue(runner.state.value.paused)
        assertNull(runner.state.value.currentItem)
        assertEquals(SetPhase.IDLE, runner.state.value.phase)
        assertEquals(0L, runner.state.value.remainingMs)
        advance(1000L)
        assertNull(runner.state.value.currentItem)
        runner.resume()
        runCurrent()
        assertEquals("b", runner.state.value.currentItem?.id)
        assertEquals(300L, runner.state.value.remainingMs)
        runner.stopAndJoin()
    }

    @Test
    fun coalescedDeletionsWhilePausedContinueBelowTheUpdatedVacantSlotInSamePass() = runTest {
        val a = wait("a")
        val b = wait("b")
        val c = wait("c", 1000L)
        val d = wait("d", 1000L)
        val items = MutableStateFlow<List<SetItem>>(listOf(a, b, c, d))
        val runner = runner(items)
        runner.start()
        runCurrent()
        advance(200L)
        assertEquals("c", runner.state.value.currentItem?.id)
        runner.pause()
        items.value = listOf(b, c, d)
        items.value = listOf(b, d)
        runCurrent()
        assertNull(runner.state.value.currentItem)
        runner.resume()
        runCurrent()
        assertEquals("d", runner.state.value.currentItem?.id)
        assertEquals(1L, runner.state.value.pass)
        runner.stopAndJoin()
    }

    @Test
    fun deletingAllItemsWhilePausedStopsWithoutRequiringResume() = runTest {
        val items = MutableStateFlow<List<SetItem>>(listOf(wait("a", 1000L)))
        val runner = runner(items)
        runner.start()
        runCurrent()
        runner.pause()
        items.value = emptyList()
        runCurrent()
        assertFalse(runner.state.value.active)
        assertFalse(runner.state.value.paused)
        assertNull(runner.state.value.currentItem)
        assertEquals(SetStopReason.EMPTY, runner.state.value.stopReason)
    }

    @Test
    fun clearingCurrentPointsWhilePausedPreservesFrozenWorkUntilItFinishes() = runTest {
        val a = touch("a", point("p"))
        val items = MutableStateFlow<List<SetItem>>(listOf(a))
        val runner = runner(items)
        runner.start()
        runCurrent()
        advance(50L)
        runner.pause()
        items.value = listOf(a.copy(points = emptyList()))
        runCurrent()
        assertTrue(runner.state.value.active)
        assertTrue(runner.state.value.paused)
        assertEquals(1, runner.state.value.currentItem?.points?.size)
        runner.resume()
        runCurrent()
        advance(100L)
        assertFalse(runner.state.value.active)
        assertEquals(SetStopReason.EMPTY, runner.state.value.stopReason)
    }

    @Test
    fun deletingLastItemStillSettlesDispatchedGestureBeforeBecomingIdle() = runTest {
        val completion = CompletableDeferred<Unit>()
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("a", point("p"))))
        val runner = runner(items, execute = { _, _ -> completion.await(); true })
        runner.start()
        runCurrent()
        items.value = emptyList()
        runCurrent()
        assertTrue(runner.state.value.active)
        assertTrue(runner.state.value.stopping)
        assertNull(runner.state.value.currentItem)
        completion.complete(Unit)
        runCurrent()
        assertFalse(runner.state.value.active)
        assertEquals(SetStopReason.EMPTY, runner.state.value.stopReason)
    }

    @Test
    fun reorderingAboveCursorDefersItemsUntilNextPass() = runTest {
        val a = touch("a", point("pa"))
        val b = touch("b", point("pb"))
        val c = touch("c", point("pc"))
        val items = MutableStateFlow<List<SetItem>>(listOf(a, b, c))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions)
        runner.start()
        runCurrent()
        items.value = listOf(b, a, c)
        runCurrent()
        advance(150L)
        assertEquals(listOf("pa", "pc"), actions.map { it.id })
        advance(150L)
        assertEquals(listOf("pa", "pc", "pb"), actions.map { it.id })
        assertEquals(2L, runner.state.value.pass)
        runner.stopAndJoin()
    }

    @Test
    fun currentPointerSnapshotSurvivesEditsButUpcomingItemUsesNewPoints() = runTest {
        val a = touch("a", point("old-a"))
        val b = touch("b", point("old-b"))
        val items = MutableStateFlow<List<SetItem>>(listOf(a, b))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions)
        runner.start()
        runCurrent()
        items.value = listOf(a.copy(points = listOf(point("new-a"))), b.copy(points = listOf(point("new-b"))))
        runCurrent()
        assertEquals("old-a", runner.state.value.currentItem?.points?.first()?.id)
        advance(150L)
        assertEquals("new-b", actions.last().id)
        advance(150L)
        assertEquals("new-a", actions.last().id)
        runner.stopAndJoin()
    }

    @Test
    fun sharedChangesAffectSubsequentGesturesAndKeepDispatchedDuration() = runTest {
        val items = MutableStateFlow<List<SetItem>>(
            listOf(touch("a", point("first", drag = true), point("second", 1, drag = true))),
        )
        var settings = SetGestureOptions(100L, 500L)
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions, options = { settings })
        runner.start()
        runCurrent()
        advance(200L)
        settings = SetGestureOptions(300L, 900L)
        advance(300L)
        assertEquals(500L, actions.first().durationMs)
        assertEquals(300L, runner.state.value.remainingMs)
        advance(300L)
        assertEquals(800L, actions.last().atMs)
        assertEquals(900L, actions.last().durationMs)
        runner.cancel()
        advance(900L)
        runner.stopAndJoin()
    }

    @Test
    fun emptyTouchItemsSkipAndRemovingLastExecutableItemStops() = runTest {
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("empty"), wait("only", 1000L)))
        val runner = runner(items)
        assertTrue(runner.start())
        runCurrent()
        assertEquals("only", runner.state.value.currentItem?.id)
        items.value = listOf(touch("empty"))
        runCurrent()
        assertFalse(runner.state.value.active)
        assertEquals(SetStopReason.EMPTY, runner.state.value.stopReason)
        assertFalse(runner.start())
    }

    @Test
    fun gestureFailureStopsAndRetainsFailingItemForExplanation() = runTest {
        val ids = mutableListOf<String>()
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("a", point("failure")), touch("b", point("never"))))
        val runner = runner(items, execute = { point, _ -> ids += point.id; false })
        runner.start()
        runCurrent()
        assertFalse(runner.state.value.active)
        assertEquals(SetStopReason.GESTURE_FAILED, runner.state.value.stopReason)
        assertEquals("a", runner.state.value.currentItem?.id)
        assertEquals(listOf("failure"), ids)
    }

    @Test
    fun initiallyPausedStartDoesNotDispatchUntilResume() = runTest {
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("a", point("p"))))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions)
        assertTrue(runner.start(paused = true))
        assertTrue(runner.state.value.active)
        assertTrue(runner.state.value.paused)
        runCurrent()
        advance(1000L)
        assertTrue(actions.isEmpty())
        runner.resume()
        runCurrent()
        assertEquals(listOf(1000L), actions.map { it.atMs })
        runner.stopAndJoin()
    }

    @Test
    fun initiallyPausedStartSelectsLatestTopItemAfterReordering() = runTest {
        val a = touch("a", point("pa"))
        val b = touch("b", point("pb"))
        val items = MutableStateFlow<List<SetItem>>(listOf(a, b))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions)
        runner.start(paused = true)
        runCurrent()
        items.value = listOf(b, a)
        runCurrent()
        runner.resume()
        runCurrent()
        assertEquals("pb", actions.single().id)
        assertEquals("b", runner.state.value.currentItem?.id)
        runner.stopAndJoin()
    }

    @Test
    fun disposeEndsRuntimeAndNeverResumesOnDataUpdates() = runTest {
        val items = MutableStateFlow<List<SetItem>>(listOf(wait("a", 1000L)))
        val runner = runner(items)
        runner.start()
        runCurrent()
        runner.dispose()
        runCurrent()
        assertFalse(runner.state.value.active)
        assertEquals(SetStopReason.INTERRUPTED, runner.state.value.stopReason)
        items.value = listOf(wait("b"))
        advance(2000L)
        assertFalse(runner.state.value.active)
        assertFalse(runner.start())
    }
    @Test fun finiteRunIncludesFinalTouchIntervalAndCanRestart() = runTest {
        val items = MutableStateFlow<List<SetItem>>(listOf(touch("a", point("p"))))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions, repeat = { false })
        runner.start()
        runCurrent()
        advance(149L)
        assertTrue(runner.state.value.active)
        advance(1L)
        assertFalse(runner.state.value.active)
        assertEquals(SetStopReason.COMPLETED, runner.state.value.stopReason)
        assertNull(runner.state.value.currentItem)
        assertEquals(1, actions.size)
        assertTrue(runner.start())
        runCurrent()
        advance(150L)
        assertEquals(2, actions.size)
        assertEquals(SetStopReason.COMPLETED, runner.state.value.stopReason)
    }

    @Test fun finiteReservedRunIncludesLastRest() = runTest {
        val reserved = SetItem.Reserved("r", "r", listOf(point("p")), ReservationConfig(1, 1, 2))
        val runner = runner(MutableStateFlow(listOf<SetItem>(reserved)), repeat = { false })
        runner.start()
        runCurrent()
        advance(3999L)
        assertTrue(runner.state.value.active)
        assertEquals(SetPhase.REST, runner.state.value.phase)
        advance(1L)
        assertEquals(SetStopReason.COMPLETED, runner.state.value.stopReason)
        assertFalse(runner.state.value.active)
    }

    @Test fun repeatOffDuringPassFinishesRemainingItems() = runTest {
        var repeat = true
        val items = MutableStateFlow<List<SetItem>>(listOf(wait("a", 200L), wait("b", 300L)))
        val runner = runner(items, repeat = { repeat })
        runner.start()
        runCurrent()
        advance(100L)
        repeat = false
        advance(399L)
        assertTrue(runner.state.value.active)
        assertEquals("b", runner.state.value.currentItem?.id)
        advance(1L)
        assertEquals(SetStopReason.COMPLETED, runner.state.value.stopReason)
    }

    @Test fun repeatOnBeforeBoundaryContinuesAndLaterOffFinishesCurrentPass() = runTest {
        var repeat = false
        val runner = runner(MutableStateFlow(listOf<SetItem>(wait("a", 200L))), repeat = { repeat })
        runner.start()
        runCurrent()
        advance(100L)
        repeat = true
        advance(100L)
        assertTrue(runner.state.value.active)
        assertEquals(2L, runner.state.value.pass)
        repeat = false
        advance(200L)
        assertEquals(SetStopReason.COMPLETED, runner.state.value.stopReason)
    }

    @Test fun pausedFinalWaitUsesLatestRepeatOnResumeAndCancelStillWins() = runTest {
        var repeat = true
        val items = MutableStateFlow<List<SetItem>>(listOf(wait("a", 200L)))
        val runner = runner(items, repeat = { repeat })
        runner.start()
        runCurrent()
        advance(100L)
        runner.pause()
        repeat = false
        advance(5000L)
        assertTrue(runner.state.value.active)
        assertTrue(runner.state.value.paused)
        runner.resume()
        runCurrent()
        advance(100L)
        assertEquals(SetStopReason.COMPLETED, runner.state.value.stopReason)
        runner.start()
        runCurrent()
        runner.stopAndJoin()
        assertEquals(SetStopReason.CANCELLED, runner.state.value.stopReason)
    }

    @Test fun finitePassKeepsReorderDeferralRule() = runTest {
        val a = touch("a", point("pa"))
        val b = touch("b", point("pb"))
        val c = touch("c", point("pc"))
        val items = MutableStateFlow<List<SetItem>>(listOf(a, b, c))
        val actions = mutableListOf<Action>()
        val runner = runner(items, actions, repeat = { false })
        runner.start()
        runCurrent()
        advance(160L)
        items.value = listOf(c, a, b)
        runCurrent()
        advance(200L)
        assertEquals(listOf("pa", "pb"), actions.map { it.id })
        assertEquals(SetStopReason.COMPLETED, runner.state.value.stopReason)
    }

}
