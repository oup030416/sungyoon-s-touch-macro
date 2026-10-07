package com.sungyoon.helper.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetItemTest {
    private val point = HighlightingPoint(x = 10f, y = 20f, index = 1, delayMs = 1000L)

    @Test
    fun duplicateSeparatesEveryIdentityAndPreservesConfiguration() {
        val original = SetItem.Reserved(
            name = "original",
            points = listOf(point),
            reservation = ReservationConfig(12, 8, 3),
        )
        val duplicate = original.duplicate("copy") as SetItem.Reserved

        assertNotEquals(original.id, duplicate.id)
        assertNotEquals(original.points.first().id, duplicate.points.first().id)
        assertEquals("copy", duplicate.name)
        assertEquals(original.reservation, duplicate.reservation)
        assertEquals(original.points.first().x, duplicate.points.first().x, 0f)

        val edited = duplicate.withPoints(listOf(duplicate.points.first().copy(x = 90f)))
        assertEquals(10f, original.points.first().x, 0f)
        assertEquals(90f, edited.points.first().x, 0f)
        assertEquals("original", original.name)
    }

    @Test
    fun orderedMixedItemsRoundTripWithoutGlobalSettings() {
        val items: List<SetItem> = listOf(
            SetItem.Wait(id = "wait", name = "wait", durationMs = 300L),
            SetItem.Touch(id = "touch", name = "touch", points = listOf(point)),
            SetItem.Reserved(id = "reserved", name = "reserved", points = listOf(point)),
        )
        val json = Json { encodeDefaults = true; classDiscriminator = "kind" }
        val serializer = ListSerializer(SetItem.serializer())
        val encoded = json.encodeToString(serializer, items)
        val decoded = json.decodeFromString(serializer, encoded)

        assertEquals(items, decoded)
        assertEquals(listOf("wait", "touch", "reserved"), decoded.map { it.id })
        assertTrue(encoded.contains("\"kind\":\"reserved\""))
        assertEquals(ReservationConfig(60, 30, 1), (decoded.last() as SetItem.Reserved).reservation)
    }

    @Test
    fun defaultsAndExecutabilityMatchItemTypes() {
        assertFalse(SetItem.Touch(name = "empty").isExecutable)
        assertFalse(SetItem.Reserved(name = "empty").isExecutable)
        assertTrue(SetItem.Touch(name = "touch", points = listOf(point)).isExecutable)
        val wait = SetItem.Wait(name = "wait")
        assertTrue(wait.isExecutable)
        assertEquals(1000L, wait.durationMs)
        assertEquals(SetItemType.WAIT, wait.type)
        assertTrue(wait.points.isEmpty())
        assertEquals(wait, wait.withPoints(listOf(point)))
    }

    @Test
    fun waitValidationRejectsIncompleteStepsAndOutOfRangeValues() {
        listOf(100L, 200L, 1000L, 3_600_000L).forEach {
            assertTrue(it.toString(), SetItem.Wait.isValidDuration(it))
        }
        listOf(-100L, 0L, 99L, 101L, 3_600_100L).forEach {
            assertFalse(it.toString(), SetItem.Wait.isValidDuration(it))
        }
    }

    @Test
    fun reservationValuesAreBoundedBeforeExecution() {
        assertEquals(ReservationConfig(1, 3600, 9999), ReservationConfig(0, 9000, Int.MAX_VALUE).normalized())
    }
}
