package com.sungyoon.helper.data

import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.model.PresetPoint
import com.sungyoon.helper.model.PresetSettings
import org.junit.Assert.*
import org.junit.Test

class PresetCompatibilityTest {
    private val legacy = """[{"id":"saved-id","name":"기존 프리셋","createdAtEpochMs":123,"autoNameOrdinal":7,"points":[{"index":9,"x":100.5,"y":200.25},{"index":2,"actionType":"drag","x":30.0,"y":40.0,"dragToX":130.0,"dragToY":240.0}]}]"""

    @Test fun legacyPresetsKeepTheirIdentityOrderAndDiagonalCoordinates() {
        val entries = PresetStore.decodeEntries(legacy)
        val preset = entries.single()
        assertNull(preset.settings)
        assertEquals("saved-id", preset.id)
        assertEquals(listOf(9, 2), preset.points.map { it.index })
        assertEquals(130f, preset.points[1].dragToX)
        assertEquals(240f, preset.points[1].dragToY)
        assertEquals(entries, PresetStore.decodeEntries(PresetStore.encodeEntries(entries)))
    }

    @Test fun optionalSettingsRoundTripWithoutChangingLegacyFields() {
        val old = PresetStore.decodeEntries(legacy).single()
        val settings = PresetSettings(350, 1700, 12, false, false)
        val updated = old.copy(settings = settings)
        val restored = PresetStore.decodeEntries(PresetStore.encodeEntries(listOf(updated))).single()
        assertEquals(settings, restored.settings)
        assertEquals(old, restored.copy(settings = null))
    }

    @Test fun corruptedPresetsCannotBecomeAnEmptyList() {
        for (raw in listOf("", " ", "[", "{}", "null", "[{\"name\":\"broken\"}]")) {
            assertTrue(raw, runCatching { PresetStore.decodeEntries(raw) }.isFailure)
        }
        assertEquals(emptyList<PresetEntry>(), PresetStore.decodeEntries(null))
    }

    @Test fun retirementRemovesOnlyTheFixedIdAndClearsOnlyItsActiveConnection() {
        val old = PresetStore.decodeEntries(legacy).single()
        val hold = old.copy(id = PresetStore.LEGACY_HOLD_PRESET_ID, name = "renamed hold")
        val sameName = old.copy(id = "same-name", name = "터치 홀드 프리셋")
        val original = PresetStore.Snapshot(listOf(old, hold, sameName), hold.id, 17)
        val cleaned = PresetStore.withoutLegacyHold(original)
        assertEquals(listOf(old, sameName), cleaned.entries)
        assertNull(cleaned.activeId)
        assertEquals(17, cleaned.nextOrdinal)
        assertEquals(cleaned, PresetStore.withoutLegacyHold(cleaned))
        assertEquals(old.id, PresetStore.withoutLegacyHold(original.copy(activeId = old.id)).activeId)
        assertTrue(runCatching { PresetStore.decodeEntries(PresetStore.encodeEntries(listOf(old, old))) }.isFailure)
    }

    @Test fun mixedPointersRoundTripWithoutChangingLegacyFieldsOrOrder() {
        val old = PresetStore.decodeEntries(legacy).single()
        val hold = PresetPoint(17, "hold", 321.75f, 440.25f)
        val mixed = old.copy(points = old.points + hold)
        assertEquals(mixed, PresetStore.decodeEntries(PresetStore.encodeEntries(listOf(mixed))).single())
        val working = listOf(HighlightingPoint("h-id", hold.x, hold.y, hold.index, 350, "hold"))
        val entry = mixed.copy(points = listOf(hold))
        assertEquals(working, PresetSession.materialize(entry, working, PresetSettings()))
    }

    @Test fun restoringActivePresetPreservesWorkingPointerIdsAndPerPointValues() {
        val working = listOf(
            HighlightingPoint("tap-id", 101f, 205f, 9, 400L),
            HighlightingPoint("drag-id", 30f, 40f, 2, 900L, "drag", 130f, 240f, 2500L)
        )
        val preset = PresetEntry(name = "active", createdAtEpochMs = 0,
            points = PresetSession.toPresetPoints(working), autoNameOrdinal = 1)
        assertEquals(working, PresetSession.materialize(preset, working, PresetSettings()))
        val moved = preset.copy(points = preset.points.map { if (it.index == 9) it.copy(x = 400f) else it })
        val restored = PresetSession.materialize(moved, working, PresetSettings())
        assertEquals(working[1], restored[1])
        assertEquals("tap-id", restored[0].id)
        assertEquals(400L, restored[0].delayMs)
    }

    @Test fun malformedWorkingPointersFailInsteadOfBeingOverwritten() {
        assertTrue(runCatching { PointsStore.decodePoints("[{broken]") }.isFailure)
        assertTrue(runCatching { PointsStore.decodePoints(" ") }.isFailure)
    }
}
