package com.sungyoon.helper.service.set

import com.sungyoon.helper.model.SetItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetCursorTest {
    private fun item(id: String) = SetItem.Wait(id = id, name = id, durationMs = 100L)

    @Test
    fun movingUpcomingAboveCurrentDefersItForTheWholePass() {
        val a = item("a")
        val b = item("b")
        val c = item("c")
        val cursor = SetCursor()
        assertEquals(a, cursor.first(listOf(a, b, c)))
        cursor.observe(listOf(b, a, c))
        cursor.observe(listOf(a, b, c))
        assertEquals(c, cursor.next(listOf(a, b, c)))
        assertNull(cursor.next(listOf(a, b, c)))
        assertEquals(a, cursor.first(listOf(a, b, c)))
        assertEquals(b, cursor.next(listOf(a, b, c)))
    }

    @Test
    fun completedItemsMovedBelowCursorDoNotExecuteTwice() {
        val a = item("a")
        val b = item("b")
        val c = item("c")
        val cursor = SetCursor()
        cursor.first(listOf(a, b, c))
        assertEquals(b, cursor.next(listOf(a, b, c)))
        cursor.observe(listOf(b, c, a))
        assertEquals(c, cursor.next(listOf(b, c, a)))
        assertNull(cursor.next(listOf(b, c, a)))
    }

    @Test
    fun deletingCurrentUsesLastObservedVacatedPosition() {
        val a = item("a")
        val b = item("b")
        val c = item("c")
        val cursor = SetCursor()
        cursor.first(listOf(a, b, c))
        cursor.observe(listOf(b, a, c))
        cursor.observe(listOf(b, c))
        assertEquals(c, cursor.next(listOf(b, c)))
    }
}
