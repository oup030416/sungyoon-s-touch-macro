package com.sungyoon.helper.overlay.set

import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.service.set.SetRunState
import org.junit.Assert.assertEquals
import org.junit.Test

class SetProgressTest {
    private val current = SetItem.Wait(id = "wait", name = "Wait", durationMs = 100L)
    private fun state(position: Int, progress: Float, count: Int = 4) = SetRunState(
        active = true, currentItem = current, itemPosition = position, itemCount = count, progress = progress,
    )

    @Test
    fun combinesCompletedItemsWithCurrentItemThenResetsOnNextTraversal() {
        assertEquals(0, SetProgress.percent(state(1, 0f)))
        assertEquals(38, SetProgress.percent(state(2, 0.5f)))
        assertEquals(75, SetProgress.percent(state(3, 1f)))
        assertEquals(100, SetProgress.percent(state(4, 1f)))
        assertEquals(0, SetProgress.percent(state(1, 0f).copy(pass = 2L)))
    }

    @Test
    fun pausingAndStoppingPreserveTheCurrentTraversalPercentage() {
        val running = state(3, 0.5f)
        assertEquals(63, SetProgress.percent(running))
        assertEquals(63, SetProgress.percent(running.copy(paused = true)))
        assertEquals(63, SetProgress.percent(running.copy(stopping = true)))
    }

    @Test
    fun idleAndMissingCurrentItemShowZeroDespiteStaleProgress() {
        val completed = state(4, 1f)
        assertEquals(0, SetProgress.percent(completed.copy(active = false)))
        assertEquals(0, SetProgress.percent(completed.copy(currentItem = null)))
        assertEquals(0, SetProgress.percent(completed.copy(itemPosition = 0)))
        assertEquals(0, SetProgress.percent(completed.copy(itemCount = 0)))
    }

    @Test
    fun malformedProgressAndPositionRemainBoundedWithoutThrowing() {
        assertEquals(50, SetProgress.percent(state(3, Float.NaN)))
        assertEquals(50, SetProgress.percent(state(3, Float.POSITIVE_INFINITY)))
        assertEquals(50, SetProgress.percent(state(3, -10f)))
        assertEquals(75, SetProgress.percent(state(3, 10f)))
        assertEquals(75, SetProgress.percent(state(Int.MAX_VALUE, 0f)))
        assertEquals(0, SetProgress.percent(state(-1, 0.5f)))
        assertEquals(0, SetProgress.percent(state(1, 0.5f, -1)))
    }
}
