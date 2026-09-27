@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package com.sungyoon.helper

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sungyoon.helper.data.*
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.model.PresetSettings
import com.sungyoon.helper.overlay.pointer.PointerOverlayRootView
import com.sungyoon.helper.overlay.pointer.PointerOverlayControlsViews
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Fixture writes are restricted to the disposable AVD and are restored in finally. */
class PresetPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun testLegacyDataBackupAutosaveRecoveryAndCorruptionProtection() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val avd = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("getprop ro.boot.qemu.avd_name"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals("SungyoonHoldTest", avd)
        val context = instrumentation.targetContext
        val presets = dataStore(context, "PresetStoreKt", "getPresetDataStore")
        val points = dataStore(context, "PointsStoreKt", "getDataStore")
        val presetKey = stringPreferencesKey("presets_json")
        val pointsKey = stringPreferencesKey("points_json")
        val rawPoints = """[{"id":"tap-original","x":210.0,"y":310.0,"index":9,"delayMs":800},{"id":"drag-original","x":110.0,"y":420.0,"index":2,"delayMs":1200,"actionType":"drag","dragToX":230.0,"dragToY":610.0,"dragDurationMs":1800}]"""
        val rawPresets = """[{"id":"legacy-a","name":"기존 A","createdAtEpochMs":100,"autoNameOrdinal":0,"points":[{"index":8,"x":270.0,"y":390.0},{"index":3,"actionType":"drag","x":110.0,"y":220.0,"dragToX":230.0,"dragToY":440.0}]},{"id":"legacy-b","name":"기존 B","createdAtEpochMs":200,"autoNameOrdinal":1,"points":[{"index":4,"x":350.0,"y":480.0}]}]"""
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                SungyoonHelperService.requestHoldStop()
                SungyoonHelperService.awaitHoldStopped()
                TouchPointerOverlay.flushAndHide()
            }
            val originalPresets = presets.data.first()
            val originalPoints = points.data.first()
            val originalSettings = PresetSettings(TapIntervalStore.tapIntervalMsFlow(context).first(),
                DragDurationStore.dragDurationMsFlow(context).first(), RandomTouchRadiusStore.randomTouchRadiusDpFlow(context).first(),
                SequencePrefsStore.repeatEnabledFlow(context).first(), SequencePrefsStore.touchAnimationEnabledFlow(context).first())
            val reservation = Triple(ReservationPrefsStore.runSecondsFlow(context).first(),
                ReservationPrefsStore.restSecondsFlow(context).first(), ReservationPrefsStore.repeatCountFlow(context).first())
            val pointerSize = PointerSizeStore.pointerSizeLevelFlow(context).first()
            try {
                presets.edit { it.clear(); it[presetKey] = rawPresets; it[intPreferencesKey("next_auto_name_ordinal")] = 2 }
                points.edit { it.clear(); it[pointsKey] = rawPoints; it[intPreferencesKey("points_schema")] = 2 }
                resetSession()
                PresetSession.initialize(context)
                val oldEntries = PresetStore.decodeEntries(rawPresets)
                assertEquals(oldEntries, PresetStore.read(context).entries)
                assertEquals(rawPoints, points.data.first()[pointsKey])
                val unbound = PresetSession.state.value.points

                // Upgrade from a renamed, active built-in must retain the ordinary working data.
                presets.edit {
                    it[presetKey] = PresetStore.encodeEntries(oldEntries + PresetEntry(
                        id = "builtin_touch_hold", name = "이름 바꾼 홀드", createdAtEpochMs = 0,
                        points = listOf(com.sungyoon.helper.model.PresetPoint(0, x = 400f, y = 600f)), autoNameOrdinal = -1))
                    it[stringPreferencesKey("active_preset_id")] = "builtin_touch_hold"
                }
                resetSession()
                PresetSession.initialize(context)
                assertNull(PresetSession.state.value.activeId)
                assertEquals(oldEntries, PresetStore.read(context).entries)
                assertEquals(rawPoints, points.data.first()[pointsKey])
                resetSession()
                PresetSession.initialize(context)
                assertEquals(oldEntries, PresetStore.read(context).entries)
                withContext(Dispatchers.Main.immediate) { PresetSession.activate(context, "legacy-a") }
                val backup = PresetStore.read(context).entries.single { it.name == context.getString(R.string.preset_previous_touches) }
                assertEquals(PresetSession.toPresetPoints(unbound), backup.points)
                assertEquals(originalSettings, backup.settings)
                assertEquals(originalSettings, PresetSession.state.value.settings)
                assertEquals(oldEntries[0].points, PresetSession.toPresetPoints(PresetSession.state.value.points))
                val untouched = PresetStore.read(context).entries.single { it.id == "legacy-b" }
                val editId = PresetSession.state.value.points.first().id
                val beforeEdit = PresetSession.state.value.points
                PresetSession.editPoints(context, "legacy-a") { list -> list.map {
                    if (it.id == editId) it.copy(x = 290f, dragToX = 290f) else it
                } }
                assertEquals(beforeEdit[1], PresetSession.state.value.points[1])
                assertEquals(editId, PresetSession.state.value.points[0].id)
                PresetSession.editPoints(context, "legacy-a") { it + HighlightingPoint(
                    id = "mixed-hold", x = 321.25f, y = 540.75f, index = 20, delayMs = 800, actionType = "hold") }
                PointsStore.updatePointPosition(context, "mixed-hold", 325.5f, 544.5f)
                assertEquals("hold", PointsStore.pointsFlow(context).first().single { it.id == "mixed-hold" }.actionType)
                // Recover canonical preset content after an interrupted working-store write.
                resetSession()
                PresetSession.initialize(context)
                assertEquals(321.25f, PointsStore.pointsFlow(context).first().single { it.id == "mixed-hold" }.x, 0f)
                val beforeOpen = presets.data.first()[presetKey]
                withContext(Dispatchers.Main.immediate) { TouchPointerOverlay.show(context) }
                withTimeout(5_000) { while (overlayRoot()?.getSelectedPresetId() != "legacy-a") delay(25) }
                assertEquals("Opening the manager must not edit a preset", beforeOpen, presets.data.first()[presetKey])
                withContext(Dispatchers.Main.immediate) {
                    controls(overlayRoot()!!).intervalEdit.setText("0.6")
                    TouchPointerOverlay.hide()
                }
                withTimeout(5_000) { while (TouchPointerOverlay.isShowing()) delay(25) }
                assertEquals("Closing must flush input before the 250ms debounce", 600L,
                    PresetStore.read(context).entries.single { it.id == "legacy-a" }.settings!!.tapIntervalMs)
                val custom = PresetSettings(450, 1900, 11, false, false)
                PresetSession.editSettings(context, "legacy-a", custom)
                withContext(Dispatchers.Main.immediate) { PresetSession.activate(context, "legacy-b") }
                val activeB = PresetSession.state.value.points
                PresetSession.editPoints(context, "legacy-a") { emptyList() }
                PresetSession.editSettings(context, "legacy-a", PresetSettings())
                assertEquals(activeB, PresetSession.state.value.points)
                assertEquals(custom, PresetStore.read(context).entries.single { it.id == "legacy-a" }.settings)
                assertEquals(untouched.points, PresetStore.read(context).entries.single { it.id == "legacy-b" }.points)

                withContext(Dispatchers.Main.immediate) { PresetSession.addCurrent(context) }
                val createdId = PresetSession.state.value.activeId!!
                assertFalse(createdId == "legacy-b")
                val canonical = PresetSession.state.value.points
                points.edit { it[pointsKey] = rawPoints }
                resetSession()
                PresetSession.initialize(context)
                assertEquals(createdId, PresetSession.state.value.activeId)
                assertEquals(PresetSession.toPresetPoints(canonical), PresetSession.toPresetPoints(PointsStore.pointsFlow(context).first()))
                PresetSession.delete(context, createdId)
                assertNull(PresetSession.state.value.activeId)
                assertEquals(PresetSession.toPresetPoints(canonical), PresetSession.toPresetPoints(PresetSession.state.value.points))

                val validPresets = presets.data.first()[presetKey]!!
                presets.edit { it[presetKey] = "[broken" }
                resetSession()
                assertTrue(runCatching { PresetSession.initialize(context) }.isFailure)
                assertFalse(PresetSession.state.value.ready)
                assertTrue(runCatching { PresetSession.activate(context, "legacy-a") }.isFailure)
                assertEquals("[broken", presets.data.first()[presetKey])
                presets.edit { it[presetKey] = validPresets }
                points.edit { it[pointsKey] = "[broken-points" }
                resetSession()
                assertTrue(runCatching { PresetSession.initialize(context) }.isFailure)
                assertEquals("[broken-points", points.data.first()[pointsKey])
                assertEquals(validPresets, presets.data.first()[presetKey])
                assertEquals(reservation, Triple(ReservationPrefsStore.runSecondsFlow(context).first(),
                    ReservationPrefsStore.restSecondsFlow(context).first(), ReservationPrefsStore.repeatCountFlow(context).first()))
                assertEquals(pointerSize, PointerSizeStore.pointerSizeLevelFlow(context).first())
            } finally {
                presets.updateData { originalPresets }
                points.updateData { originalPoints }
                TapIntervalStore.setTapIntervalMs(context, originalSettings.tapIntervalMs)
                DragDurationStore.setDragDurationMs(context, originalSettings.dragDurationMs)
                RandomTouchRadiusStore.setRandomTouchRadiusDp(context, originalSettings.randomRadiusDp)
                SequencePrefsStore.setRepeatEnabled(context, originalSettings.repeatEnabled)
                SequencePrefsStore.setTouchAnimationEnabled(context, originalSettings.touchAnimationEnabled)
                resetSession()
                PresetSession.initialize(context)
            }
        }
    }

    @Test fun programmaticSettingsDoNotAutosaveAndAllPointerTypesCanBeEdited() {
        instrumentation.runOnMainSync {
            val view = PointerOverlayRootView(instrumentation.targetContext)
            var edits = 0
            view.setOnTapIntervalChanged { edits++ }
            view.setOnDragDurationChanged { edits++ }
            view.setEditingState(true)
            view.setTapIntervalSeconds(2.5f)
            view.setDragDurationSeconds(1.8f)
            assertEquals(0, edits)
            controls(view).intervalEdit.setText("0.7")
            assertEquals(1, edits)
            view.setEditingState(false)
            val buttons = controls(view)
            assertFalse(buttons.addBtn.isEnabled)
            assertFalse(buttons.addDragBtn.isEnabled)
            assertFalse(buttons.clearAllBtn.isEnabled)
            assertFalse(buttons.playToggleBtn.isEnabled)
            assertFalse(buttons.reserveBtn.isEnabled)
            assertFalse(buttons.intervalEdit.isEnabled)
            assertFalse(buttons.dragDurationEdit.isEnabled)
            assertFalse(buttons.repeatToggleBtn.isEnabled)
            assertFalse(buttons.touchAnimToggleBtn.isEnabled)
            assertTrue(buttons.presetListBtn.isEnabled)
            assertTrue(buttons.closeBtn.isEnabled)
            assertTrue(buttons.collapseBtn.isEnabled)
            val panel = PointerOverlayRootView::class.java.getDeclaredField("presetPanel").apply { isAccessible = true }.get(view)
            val addPreset = panel.javaClass.getDeclaredField("addCurrentBtn").apply { isAccessible = true }.get(panel) as android.widget.Button
            assertFalse(addPreset.isEnabled)
            assertEquals("프리셋 추가", addPreset.text.toString())
            assertEquals("H2", instrumentation.targetContext.getString(R.string.hold_pointer_label, 2))
            view.setEditingState(true)
            assertTrue(buttons.addHoldBtn.isEnabled)
            assertTrue(buttons.addBtn.isEnabled)
            assertTrue(buttons.intervalEdit.isEnabled)
        }
    }

    private fun overlayRoot(): PointerOverlayRootView? {
        val controller = TouchPointerOverlay::class.java.getDeclaredField("controller").apply { isAccessible = true }
            .get(TouchPointerOverlay) ?: return null
        return controller.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(controller) as? PointerOverlayRootView
    }

    private fun controls(view: PointerOverlayRootView): PointerOverlayControlsViews =
        PointerOverlayRootView::class.java.getDeclaredField("controls").apply { isAccessible = true }
            .get(view) as PointerOverlayControlsViews

    private fun resetSession() {
        val state = PresetSession::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(PresetSession) as MutableStateFlow<PresetSession.State>
        state.value = PresetSession.State()
    }

    private fun dataStore(context: Context, owner: String, getter: String): DataStore<Preferences> =
        Class.forName("com.sungyoon.helper.data.$owner").getDeclaredMethod(getter, Context::class.java).apply {
            isAccessible = true
        }.invoke(null, context) as DataStore<Preferences>
}
