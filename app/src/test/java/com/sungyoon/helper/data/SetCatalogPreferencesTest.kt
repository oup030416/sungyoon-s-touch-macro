package com.sungyoon.helper.data

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.ReservationConfig
import com.sungyoon.helper.model.SetDefinition
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.model.SetItemType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SetCatalogPreferencesTest {
    @Test fun duplicatePreservesConfigurationAndSeparatesIdentitiesAndCounters() {
        val point = HighlightingPoint(id = "point", x = 2519f, y = 999f, index = 4, delayMs = 1234L,
            actionType = HighlightingPoint.ACTION_TYPE_DRAG, dragToX = 0f, dragToY = 1250f, dragDurationMs = 500L)
        val original = SetDefinition("source", "세트 이름", listOf(
            SetItem.Touch("touch", "일반 3", listOf(point)),
            SetItem.Reserved("reserved", "예약 4", listOf(point), ReservationConfig(37, 19, 8)),
            SetItem.Wait("wait", "대기 5", 1200L)), true)
        val prefs = mutablePreferencesOf()
        SetCatalogPreferences.initialize(prefs, "first")
        SetCatalogPreferences.write(prefs, listOf(original, SetDefinition("other", "Other")))
        SetItemType.entries.forEach { prefs[SetCatalogPreferences.ordinalKey(original.id, it)] = 42 }
        val copy = checkNotNull(SetCatalogPreferences.duplicate(prefs, original.id) { "$it (복제)" })
        val saved = SetCatalogPreferences.read(prefs)
        assertEquals(listOf("source", "other", copy.id), saved.map { it.id })
        assertEquals(original, saved.first())
        assertEquals("세트 이름 (복제)", copy.name)
        assertTrue(copy.repeatEnabled)
        original.items.zip(copy.items).forEach { (before, after) ->
            assertNotEquals(before.id, after.id)
            assertEquals(before, after.renamed(before.name).let {
                when (it) {
                    is SetItem.Touch -> it.copy(id = before.id, points = before.points)
                    is SetItem.Reserved -> it.copy(id = before.id, points = before.points)
                    is SetItem.Wait -> it.copy(id = before.id)
                }
            })
            before.points.zip(after.points).forEach { (a, b) ->
                assertNotEquals(a.id, b.id)
                assertEquals(a, b.copy(id = a.id))
            }
        }
        SetItemType.entries.forEach { type ->
            assertEquals(42, prefs[SetCatalogPreferences.ordinalKey(copy.id, type)])
            prefs[SetCatalogPreferences.ordinalKey(copy.id, type)] = 43
            assertEquals(42, prefs[SetCatalogPreferences.ordinalKey(original.id, type)])
        }
        SetCatalogPreferences.update(prefs, copy.id) { it.copy(items = emptyList(), repeatEnabled = false) }
        assertEquals(original, SetCatalogPreferences.read(prefs).first())
    }

    @Test fun duplicateDeletedSourceDoesNotWriteAndEmptyCopiesRemainIndependent() {
        val prefs = mutablePreferencesOf()
        SetCatalogPreferences.initialize(prefs, "first")
        SetCatalogPreferences.write(prefs, listOf(SetDefinition("empty", "빈 세트")))
        val before = prefs.asMap().toMap()
        assertNull(SetCatalogPreferences.duplicate(prefs, "deleted") { error("Missing source") })
        assertEquals(before, prefs.asMap())
        val first = checkNotNull(SetCatalogPreferences.duplicate(prefs, "empty") { "$it (복제)" })
        val second = checkNotNull(SetCatalogPreferences.duplicate(prefs, first.id) { "$it (복제)" })
        assertEquals("빈 세트 (복제) (복제)", second.name)
        assertFalse(second.repeatEnabled)
        assertTrue(second.items.isEmpty())
        assertEquals(3, SetCatalogPreferences.read(prefs).map { it.id }.distinct().size)
        assertEquals(1, prefs[SetCatalogPreferences.ordinalKey(second.id, SetItemType.WAIT)])
    }

    private val codec = Json { classDiscriminator = "kind"; encodeDefaults = true }
    private fun legacy(items: List<SetItem>) = codec.encodeToString(ListSerializer(SetItem.serializer()), items)

    @Test fun migrationPreservesEveryItemFieldAndNameCounter() {
        val points = listOf(HighlightingPoint(id = "point", x = 2519f, y = 999f, index = 4, delayMs = 1234L,
            actionType = HighlightingPoint.ACTION_TYPE_DRAG, dragToX = 0f, dragToY = 1250f, dragDurationMs = 500L))
        val items = listOf(SetItem.Touch("touch", "custom", points),
            SetItem.Reserved("reserved", "reserved", points, ReservationConfig(37, 19, 8)),
            SetItem.Wait("wait", "wait", 1200L))
        val raw = legacy(items)
        val prefs = mutablePreferencesOf(SetCatalogPreferences.legacyItems to raw,
            intPreferencesKey("next_touch_ordinal") to 42,
            intPreferencesKey("next_reserved_ordinal") to 12,
            intPreferencesKey("next_wait_ordinal") to 9)
        SetCatalogPreferences.initialize(prefs, "first") { "set" }
        assertEquals(listOf(SetDefinition("set", "first", items, true)), SetCatalogPreferences.read(prefs))
        assertEquals(raw, prefs[SetCatalogPreferences.legacyItems])
        assertEquals(42, prefs[SetCatalogPreferences.ordinalKey("set", SetItemType.TOUCH)])
        assertEquals(12, prefs[SetCatalogPreferences.ordinalKey("set", SetItemType.RESERVED)])
        assertEquals(9, prefs[SetCatalogPreferences.ordinalKey("set", SetItemType.WAIT)])
        assertEquals(2, prefs[SetCatalogPreferences.nextSetOrdinal])
    }

    @Test fun repeatedMigrationDoesNotDuplicateOrChangeIds() {
        val prefs = mutablePreferencesOf(SetCatalogPreferences.legacyItems to legacy(listOf(SetItem.Wait("id", "name"))))
        SetCatalogPreferences.initialize(prefs, "first") { "original" }
        val expected = prefs.asMap().toMap()
        SetCatalogPreferences.initialize(prefs, "different") { error("Must not generate another ID") }
        assertEquals(expected, prefs.asMap())
    }

    @Test fun newInstallIsEmptyAndStartsNamingAtOne() {
        val prefs = mutablePreferencesOf()
        SetCatalogPreferences.initialize(prefs, "first")
        assertTrue(SetCatalogPreferences.read(prefs).isEmpty())
        assertEquals(1, prefs[SetCatalogPreferences.nextSetOrdinal])
        assertFalse(SetDefinition(name = "new").repeatEnabled)
    }

    @Test fun existingEmptyLegacyListStillMigratesWithRepeatOn() {
        val prefs = mutablePreferencesOf(SetCatalogPreferences.legacyItems to "[]")
        SetCatalogPreferences.initialize(prefs, "first") { "empty" }
        assertEquals(listOf(SetDefinition("empty", "first", emptyList(), true)), SetCatalogPreferences.read(prefs))
    }

    @Test fun deletingEverySetAndRestartingNeverResurrectsLegacyData() {
        val prefs = mutablePreferencesOf(SetCatalogPreferences.legacyItems to legacy(listOf(SetItem.Wait("id", "name"))))
        SetCatalogPreferences.initialize(prefs, "first")
        SetCatalogPreferences.write(prefs, emptyList())
        SetCatalogPreferences.initialize(prefs, "first") { error("Must not migrate again") }
        assertTrue(SetCatalogPreferences.read(prefs).isEmpty())
    }

    @Test fun malformedLegacyFailsWithoutWritingAnyMigrationStateAndCanRetry() {
        val prefs = mutablePreferencesOf(SetCatalogPreferences.legacyItems to "broken")
        val before = prefs.asMap().toMap()
        assertTrue(runCatching { SetCatalogPreferences.initialize(prefs, "first") }.isFailure)
        assertEquals(before, prefs.asMap())
        prefs[SetCatalogPreferences.legacyItems] = "[]"
        SetCatalogPreferences.initialize(prefs, "first") { "retry" }
        assertEquals("retry", SetCatalogPreferences.read(prefs).single().id)
    }

    @Test fun existingCatalogRecoveryDoesNotOverwriteWithLegacy() {
        val prefs = mutablePreferencesOf(SetCatalogPreferences.legacyItems to "broken")
        val expected = listOf(SetDefinition("saved", "saved"))
        SetCatalogPreferences.write(prefs, expected)
        SetCatalogPreferences.initialize(prefs, "first")
        assertEquals(expected, SetCatalogPreferences.read(prefs))
    }

    @Test fun editsWithMatchingItemIdsStayInsideTheirSet() {
        val item = SetItem.Wait("same-item-id", "wait", 100L)
        val prefs = mutablePreferencesOf()
        SetCatalogPreferences.initialize(prefs, "first")
        val a = SetDefinition("a", "A", listOf(item))
        val b = SetDefinition("b", "B", listOf(item))
        SetCatalogPreferences.write(prefs, listOf(a, b))
        assertTrue(SetCatalogPreferences.update(prefs, "b") { it.copy(items = listOf(item.copy(durationMs = 500L)), repeatEnabled = true) })
        val result = SetCatalogPreferences.read(prefs)
        assertEquals(a, result[0])
        assertEquals(500L, (result[1].items.single() as SetItem.Wait).durationMs)
        assertTrue(result[1].repeatEnabled)
    }

    @Test fun lateMutationOfDeletedSetDoesNotRecreateOrAlterAnotherSet() {
        val prefs = mutablePreferencesOf()
        SetCatalogPreferences.initialize(prefs, "first")
        val b = SetDefinition("b", "B")
        SetCatalogPreferences.write(prefs, listOf(b))
        assertFalse(SetCatalogPreferences.update(prefs, "deleted") { it.copy(name = "late") })
        assertEquals(listOf(b), SetCatalogPreferences.read(prefs))
    }

    @Test fun countersAreIndependentAndCatalogOrderSurvivesChanges() {
        val prefs = mutablePreferencesOf()
        SetCatalogPreferences.initialize(prefs, "first")
        prefs[SetCatalogPreferences.ordinalKey("a", SetItemType.WAIT)] = 17
        assertNull(prefs[SetCatalogPreferences.ordinalKey("b", SetItemType.WAIT)])
        SetCatalogPreferences.write(prefs, listOf(SetDefinition("a", "A"), SetDefinition("b", "B")))
        SetCatalogPreferences.update(prefs, "a") { it.copy(name = "new", repeatEnabled = true) }
        assertEquals(listOf("a", "b"), SetCatalogPreferences.read(prefs).map { it.id })
        assertFalse(SetCatalogPreferences.read(prefs)[1].repeatEnabled)
    }
}
