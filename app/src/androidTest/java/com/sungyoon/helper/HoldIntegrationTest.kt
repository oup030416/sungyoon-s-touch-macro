@file:Suppress("DEPRECATION")

package com.sungyoon.helper

import android.app.UiAutomation
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PathMeasure
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.InputEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.sungyoon.helper.data.PresetSession
import com.sungyoon.helper.data.PresetStore
import com.sungyoon.helper.data.PointsStore
import com.sungyoon.helper.data.SequencePrefsStore
import com.sungyoon.helper.data.ReservationRuntimeStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.PresetSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.overlay.floating.FloatingToggleOverlayController
import com.sungyoon.helper.overlay.pointer.PointerOverlayRootView
import com.sungyoon.helper.service.HoldGestureRunner
import com.sungyoon.helper.service.HoldGestureBackend
import com.sungyoon.helper.service.HoldStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Runs only on the dedicated disposable AVD, never on a user's phone or another emulator. */
class HoldIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var automation: UiAutomation
    private lateinit var activity: MainActivity
    private lateinit var target: RecordingView
    private lateinit var service: SungyoonHelperService
    private var isolatedDevice = false
    private val fixtureId = "mixed-hold-integration"
    private val holdId = "integration-h1"
    private var centerX = 0f
    private var centerY = 0f

    @Before
    fun setUp() {
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertEquals("Refusing to inject test touches outside the isolated AVD", "SungyoonHoldTest",
            shell("getprop ro.boot.qemu.avd_name").trim())
        isolatedDevice = true
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        // Instrumentation force-stops its target package, so rebind after the runner starts.
        shell("settings delete secure enabled_accessibility_services")
        await("The prior test service did not disconnect") { connectedService() == null }
        SystemClock.sleep(150)
        shell("settings put secure enabled_accessibility_services com.sungyoon.helper/com.sungyoon.helper.SungyoonHelperService")
        shell("settings put secure accessibility_enabled 1")
        await("The isolated test accessibility service did not connect") { connectedService() != null }
        service = connectedService()!!

        // Keep the target stable and avoid a network update request during gesture measurement.
        MainActivity::class.java.getDeclaredField("didInitialUpdateCheck").apply {
            isAccessible = true
            setBoolean(null, true)
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        instrumentation.runOnMainSync {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            target = RecordingView(activity)
            activity.setContentView(target)
        }
        await("Recording view did not receive layout and focus") {
            target.width > 0 && target.height > 0 && activity.hasWindowFocus()
        }
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            centerX = location[0] + target.width / 2f
            centerY = location[1] + target.height / 2f
        }
        sendCommand(SungyoonHelperService.ACTION_STOP_SEQUENCE)
        sendCommand(SungyoonHelperService.ACTION_STOP_RESERVATION)
        instrumentation.waitForIdleSync()
        await("The previous reservation did not stop") {
            runBlocking { !ReservationRuntimeStore.snapshotFlow(activity).first().active }
        }
        runBlocking {
            withTimeout(10_000) {
                withContext(Dispatchers.Main.immediate) {
                    SungyoonHelperService.requestHoldStop()
                    SungyoonHelperService.awaitHoldStopped()
                    TouchPointerOverlay.flushAndHide()
                    PresetSession.initialize(activity)
                    val fixture = PresetEntry(id = fixtureId, name = "혼합 홀드 검증", createdAtEpochMs = 0,
                        points = emptyList(), autoNameOrdinal = -1, settings = PresetSettings(randomRadiusDp = 0,
                            repeatEnabled = false, touchAnimationEnabled = false))
                    PresetStore.update(activity) { it.copy(entries = it.entries.filterNot { p -> p.id == fixtureId } + fixture) }
                    PresetSession.activate(activity, fixtureId)
                    PresetSession.editSettings(activity, fixtureId, PresetSettings(randomRadiusDp = 0,
                        repeatEnabled = false, touchAnimationEnabled = false))
                    PresetSession.editPoints(activity, fixtureId) {
                        listOf(HighlightingPoint(holdId, centerX, centerY, 0, 1000, "hold"))
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        target.events.clear()
    }

    @After
    fun tearDown() {
        if (!isolatedDevice) return
        try {
            if (::service.isInitialized) {
                sendCommand(SungyoonHelperService.ACTION_STOP_SEQUENCE)
                sendCommand(SungyoonHelperService.ACTION_STOP_RESERVATION)
                instrumentation.runOnMainSync { setPlayback(false) }
                runBlocking {
                    withTimeout(5_000) {
                        withContext(Dispatchers.Main.immediate) {
                            (serviceField("holdRunner") as HoldGestureRunner).stopAllAndAwait()
                            TouchPointerOverlay.flushAndHide()
                        }
                    }
                }
            }
        } finally {
            if (::automation.isInitialized) {
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
                automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            }
            if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun testContinuousHoldKeepsOnePointerDownUntilOff() {
        startAndAwaitDown()
        SystemClock.sleep(2_200)
        assertEquals("A hold must not become repeated taps", 1, target.downCount())
        assertEquals("A continued chunk must not lift the finger", 0, target.endCount())
        assertTrue(isHolding())
        assertTrue("Continued chunks must contain a movement sample", target.events.any { it.action == MotionEvent.ACTION_MOVE })
        target.events.forEach {
            assertTrue("A hold moved more than one physical pixel", abs(it.x - centerX.roundToInt()) <= 1f)
            assertEquals("A hold moved vertically", centerY.roundToInt().toFloat(), it.y, 0f)
        }

        instrumentation.runOnMainSync { setPlayback(false) }
        awaitReleased()
        assertEquals(1, target.events.count { it.action == MotionEvent.ACTION_UP })
        assertEquals(0, target.events.count { it.action == MotionEvent.ACTION_CANCEL })
        assertTrue("The hold was shorter than the observation interval",
            target.events.last().time - target.events.first().time >= 2_200)
        assertEquals(centerX.roundToInt().toFloat(), target.events.last().x, 0f)
        val recorded = target.events.size
        SystemClock.sleep(350)
        assertEquals("Off must not schedule another stroke", recorded, target.events.size)

        target.events.clear()
        injectTap(centerX + 40f, centerY)
        await("A normal touch was not received after Off") { target.endCount() == 1 }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), target.events.map { it.action })
    }

    @Test
    fun testRapidOnOffDoesNotLeaveAPointerOrRestart() {
        configureMixed(repeat = true)
        startAndAwaitDown()
        repeat(8) {
            instrumentation.runOnMainSync {
                setPlayback(false)
                setPlayback(true)
                setPlayback(false)
                setPlayback(true)
                setPlayback(false)
            }
            SystemClock.sleep(20)
        }
        awaitReleased()
        awaitPlaybackOff()

        val completedStarts = target.downCount()
        instrumentation.runOnMainSync { setPlayback(true) }
        await("On after a cancelled command chain did not start") { target.downCount() > completedStarts && isHolding() }
        SystemClock.sleep(300)
        assertTrue("An older Off cleanup released the newest holds", isHolding())
        assertTrue("An older command cleared the newest On state", isPlaybackOn())
        instrumentation.runOnMainSync { setPlayback(false) }
        awaitReleased()
        var down = false
        for (event in target.events) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    assertFalse("A new run started before the previous pointer was released", down)
                    down = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> down = false
            }
        }
        assertFalse("A pointer remains pressed after rapid Off", down)
        awaitPlaybackOff()
        assertNoRestart()
    }

    @Test
    fun testFloatingOffDoesNotRestartAfterPhysicalTouchCancellation() {
        startAndAwaitDown()
        tapPlaybackControl(expectedEnabled = false)
        awaitReleased()
        assertNoRestart()
    }

    @Test
    fun testFloatingOnWithManagerClosedAndFractionalAnchor() {
        assertFloatingOnAtFractionalAnchor(managerOpen = false)
    }

    @Test
    fun testFloatingOnAfterPhysicalPointerDragWithManagerOpen() {
        assertFloatingOnAtFractionalAnchor(managerOpen = true)
    }

    private fun assertFloatingOnAtFractionalAnchor(managerOpen: Boolean) {
        centerX = centerX.roundToInt() + 0.9365f
        centerY = centerY.roundToInt() + 0.15625f
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                PresetSession.editPoints(activity, fixtureId) { points ->
                    points.map { it.copy(x = centerX, y = centerY, dragToX = centerX, dragToY = centerY) }
                }
            }
        }
        try {
            if (managerOpen) {
                instrumentation.runOnMainSync { TouchPointerOverlay.show(activity) }
                await("The hold manager did not finish opening") {
                    overlayRoot()?.getSelectedPresetId() == fixtureId
                }
                instrumentation.waitForIdleSync()
                dragHoldPointerBeforeFirstOn()
            }
            assertEquals(managerOpen, TouchPointerOverlay.isShowing())
            // Exercise the actual physical-style DOWN/UP button path, not setPlayback reflection.
            tapPlaybackControl(expectedEnabled = true)
            awaitHoldDown()
            SystemClock.sleep(2_200)
            assertTrue("The actual On button did not keep the hold running", isHolding())
            assertEquals("On must inject only one initial DOWN", 1, target.downCount())
            assertEquals("The hold ended without an Off request", 0, target.endCount())
            assertFalse("On must close the manager", TouchPointerOverlay.isShowing())
            assertFalse(target.events.first().managerVisible)
            target.events.forEach {
                assertTrue(abs(it.x - centerX.roundToInt()) <= 1f)
                assertEquals(centerY.roundToInt().toFloat(), it.y, 0f)
            }
            val savedPoint = PresetSession.state.value.points.single()
            assertEquals("Injection must not round the saved x coordinate", centerX, savedPoint.x, 0.001f)
            assertEquals("Injection must not round the saved y coordinate", centerY, savedPoint.y, 0.001f)
            assertTrue("The moved pointer must retain its fractional x coordinate", savedPoint.x % 1f != 0f)
            assertTrue("The moved pointer must retain its fractional y coordinate", savedPoint.y % 1f != 0f)
            tapPlaybackControl(expectedEnabled = false)
            awaitReleased()
            assertNoRestart()
        } finally {
            runBlocking { withContext(Dispatchers.Main.immediate) { TouchPointerOverlay.flushAndHide() } }
        }
    }

    private fun dragHoldPointerBeforeFirstOn() {
        val root = overlayRoot()!!
        val panel = root.javaClass.getDeclaredField("controlPanelScrollHost")
            .apply { isAccessible = true }.get(root) as View
        instrumentation.runOnMainSync { root.setControlPanelVisibleFromController(false) }
        await("The panel still covers the hold pointer") { panel.visibility == View.GONE }
        @Suppress("UNCHECKED_CAST")
        val pointer = (root.javaClass.getDeclaredField("views").apply { isAccessible = true }
            .get(root) as Map<String, View>).getValue(holdId)
        val start = IntArray(2)
        instrumentation.runOnMainSync {
            pointer.getLocationOnScreen(start)
            start[0] += pointer.width / 2
            start[1] += pointer.height / 2
        }
        val dx = 80.375f
        val dy = 60.3125f
        val downTime = SystemClock.uptimeMillis()
        val inject = UiAutomation::class.java.getMethod("injectInputEventToInputFilter", InputEvent::class.java)
        for (step in 0..7) {
            val fraction = step.coerceAtMost(6) / 6f
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                7 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                start[0] + dx * fraction, start[1] + dy * fraction, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { inject.invoke(automation, event) } finally { event.recycle() }
            if (step < 7) SystemClock.sleep(20)
        }
        // Do not await autosave or inject a warmup touch before the very first On click.
        centerX += dx
        centerY += dy
    }

    private fun tapPlaybackControl(expectedEnabled: Boolean) {
        val controller = serviceField("floatingToggle")!!
        val button = controller.javaClass.getDeclaredField("playbackButton").apply { isAccessible = true }.get(controller) as View
        val callbackField = controller.javaClass.getDeclaredField("onPlaybackToggle").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val callback = callbackField.get(controller) as (Boolean) -> Unit
        val requested = AtomicReference<Boolean?>()
        await("The floating playback control has not been laid out") {
            button.isShown && button.width > 0 && button.height > 0
        }
        val point = IntArray(2)
        instrumentation.runOnMainSync {
            callbackField.set(controller, { enabled: Boolean -> requested.set(enabled); callback(enabled) })
            button.getLocationOnScreen(point)
            point[0] += button.width / 2
            point[1] += button.height / 2
        }
        try {
            injectTap(point[0].toFloat(), point[1].toFloat(), throughAccessibilityFilter = true)
            await("The physical tap did not activate the floating playback control at ${point.toList()}") { requested.get() != null }
            assertEquals("The physical click requested the wrong playback state", expectedEnabled, requested.get())
        } finally {
            instrumentation.runOnMainSync { callbackField.set(controller, callback) }
        }
    }

    @Test
    fun testRotationReleasesHoldWithoutRestarting() {
        startAndAwaitDown()
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
        awaitReleased()
        assertNoRestart()
    }

    @Test
    fun testScreenOffReleasesHoldWithoutRestartingOnWake() {
        startAndAwaitDown()
        shell("input keyevent 223")
        val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        await("The isolated emulator did not turn its screen off") { !power.isInteractive }
        awaitReleased()
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        assertNoRestart()
    }

    @Test
    fun testServiceInterruptReleasesHoldWithoutRestarting() {
        startAndAwaitDown()
        instrumentation.runOnMainSync { service.onInterrupt() }
        awaitReleased()
        assertNoRestart()
    }

    @Test
    fun testServiceDisableAndReconnectKeepsHoldOff() {
        val enabledServices = shell("settings get secure enabled_accessibility_services").trim()
        assertTrue(enabledServices.matches(Regex("[A-Za-z0-9._/:]+")))
        startAndAwaitDown()
        try {
            instrumentation.runOnMainSync { service.disableSelf() }
            await("The accessibility service did not disconnect") { connectedService() == null }
            awaitReleased()
        } finally {
            shell("settings put secure enabled_accessibility_services $enabledServices")
            shell("settings put secure accessibility_enabled 1")
        }
        await("The accessibility service did not reconnect") { connectedService() != null }
        service = connectedService()!!
        assertNoRestart()
    }

    @Test
    fun testBackendSegmentsStayOnePixelInsideLeftBoundary() {
        val runner = serviceField("holdRunner") as HoldGestureRunner
        val backend = HoldGestureRunner::class.java.getDeclaredField("backend")
            .apply { isAccessible = true }.get(runner) as HoldGestureBackend
        val first = backend.createStroke(null, 0f, 640f, 100L, true)
        val second = backend.createStroke(first, 0f, 640f, 100L, true)
        val release = backend.createStroke(second, 0f, 640f, 1L, false)
        fun description(stroke: HoldStroke) = stroke.javaClass.getDeclaredField("stroke")
            .apply { isAccessible = true }.get(stroke) as GestureDescription.StrokeDescription
        fun endpoints(stroke: HoldStroke): Pair<Float, Float> {
            val measure = PathMeasure(description(stroke).path, false)
            val point = FloatArray(2)
            assertTrue(measure.getPosTan(0f, point, null))
            val start = point[0]
            assertTrue(measure.getPosTan(measure.length, point, null))
            assertEquals(640f, point[1], 0f)
            return start to point[0]
        }
        assertEquals(0f to 1f, endpoints(first))
        assertEquals(1f to 0f, endpoints(second))
        assertEquals(100L, description(first).duration)
        assertTrue(description(first).willContinue())
        assertFalse(description(release).willContinue())
    }

    @Test
    fun testManagerClosesBeforeHoldAndReopeningReleasesIt() {
        try {
            instrumentation.runOnMainSync { TouchPointerOverlay.show(activity) }
            await("The hold management overlay did not finish opening") {
                overlayRoot()?.getSelectedPresetId() == fixtureId
            }
            instrumentation.waitForIdleSync()
            startAndAwaitDown()
            assertFalse("On must close the manager", TouchPointerOverlay.isShowing())
            assertFalse("The manager was still visible when the target received DOWN",
                target.events.first { it.action == MotionEvent.ACTION_DOWN }.managerVisible)

            instrumentation.runOnMainSync { TouchPointerOverlay.show(activity) }
            awaitReleased()
            assertTrue(TouchPointerOverlay.isShowing())
            assertNoRestart()
        } finally {
            runBlocking { withContext(Dispatchers.Main.immediate) { TouchPointerOverlay.flushAndHide() } }
        }
    }

    @Test
    fun testRemovingFloatingControlsReleasesHold() {
        startAndAwaitDown()
        val floating = serviceField("floatingToggle") as FloatingToggleOverlayController
        try {
            instrumentation.runOnMainSync { floating.hide() }
            awaitReleased()
            assertFalse(floating.isShowing())
            assertNoRestart()
        } finally {
            instrumentation.runOnMainSync { floating.show() }
        }
    }

    @Test fun testTwoHoldsContinueAcrossTapsAndSegmentedDrag() {
        configureMixed(repeat = false)
        tapPlaybackControl(expectedEnabled = true)
        await("The ordinary drag did not finish") {
            target.events.count { it.action == MotionEvent.ACTION_POINTER_UP } >= 2
        }
        await("The ordinary sequence did not finish") {
            runBlocking { !SequencePrefsStore.sequenceRunningFlow(activity).first() }
        }
        assertTrue("Completion must leave holds On", isHolding())
        assertTrue("The global control must stay On while holds remain", isPlaybackOn())
        val holdIds = target.events.first { it.pointerIds.size == 2 }.pointerIds.toSet()
        assertEquals(2, holdIds.size)
        assertTrue(target.events.filter { it.action == MotionEvent.ACTION_POINTER_UP }.all { it.actionId !in holdIds })
        assertFalse(target.events.any { it.action == MotionEvent.ACTION_UP || it.action == MotionEvent.ACTION_CANCEL })
        assertEquals(1, target.downCount())
        target.events.filter { it.pointerIds.size >= 2 }.forEach {
            assertTrue("A continued hold contact disappeared", it.pointerIds.containsAll(holdIds))
        }
        assertTrue("The ordinary drag did not move while holding", target.events.any {
            it.action == MotionEvent.ACTION_MOVE && it.coordinates.any { (id, xy) -> id !in holdIds && xy.first > centerX + 60f }
        })
        instrumentation.runOnMainSync { setPlayback(false) }
        awaitReleased()
        assertEquals(1, target.events.count { it.action == MotionEvent.ACTION_UP })
        assertFalse(target.events.any { it.action == MotionEvent.ACTION_CANCEL })
    }

    @Test fun testHoldClicksStandardButtonWithoutAddingAnotherContact() {
        val clicks = java.util.concurrent.atomic.AtomicInteger()
        lateinit var button: Button
        instrumentation.runOnMainSync {
            (target.parent as android.view.ViewGroup).removeView(target)
            val container = FrameLayout(activity).apply { isMotionEventSplittingEnabled = false }
            container.addView(target, FrameLayout.LayoutParams(-1, -1))
            button = Button(activity).apply {
                contentDescription = "hold-click-test"
                setOnClickListener { clicks.incrementAndGet() }
            }
            container.addView(button, FrameLayout.LayoutParams(200, 100).apply {
                leftMargin = 40
                topMargin = 80
            })
            activity.setContentView(container)
        }
        await("Standard button did not receive layout") { button.width > 0 }
        var tapX = 0f
        var tapY = 0f
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            tapX = location[0] + button.width / 2f
            tapY = location[1] + button.height / 2f
        }
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                PresetSession.editPoints(activity, fixtureId) { points ->
                    points + HighlightingPoint("integration-button", tapX, tapY, 1, 100)
                }
            }
        }
        instrumentation.waitForIdleSync()
        startAndAwaitDown()
        await("The button was not clicked while holding") { clicks.get() == 1 }
        await("The ordinary sequence did not finish") {
            runBlocking { !SequencePrefsStore.sequenceRunningFlow(activity).first() }
        }
        assertTrue("The click released the hold", isHolding())
        assertEquals(1, target.downCount())
        assertEquals(0, target.endCount())
        assertFalse("The semantic click also injected a tap", target.events.any { it.pointerIds.size > 1 })
        instrumentation.runOnMainSync { setPlayback(false) }
        awaitReleased()
        assertEquals(1, clicks.get())
    }

    @Test fun testOrdinaryOnlyFloatingToggleTurnsOffAfterCompletion() {
        configureMixed(repeat = false)
        runBlocking { withContext(Dispatchers.Main.immediate) {
            PresetSession.editPoints(activity, fixtureId) { points -> points.filterNot { it.actionType == "hold" } }
        } }
        instrumentation.runOnMainSync {
            assertTrue("Ordinary-only playback must have a visible control", playbackButton().isShown)
            assertTrue("Ordinary-only playback must not require hold support", playbackButton().isEnabled)
        }
        tapPlaybackControl(expectedEnabled = true)
        await("The ordinary sequence did not start") { target.downCount() > 0 }
        assertTrue("Ordinary playback must show On without any holds", isPlaybackOn())
        await("The ordinary tap and drag did not finish") { target.endCount() == 2 }
        awaitPlaybackOff()
        assertEquals(2, target.downCount())
        assertFalse(isHolding())
        assertTrue(target.events.all { it.pointerIds.size == 1 })
        assertNoRestart()

        target.events.clear()
        tapPlaybackControl(expectedEnabled = true)
        await("A later On did not restart ordinary playback") { target.downCount() > 0 }
        val first = target.events.first()
        assertEquals(centerX.roundToInt().toFloat(), first.x, 0f)
        assertEquals((centerY + 150f).roundToInt().toFloat(), first.y, 0f)
        tapPlaybackControl(expectedEnabled = false)
        awaitReleased()
        awaitPlaybackOff()
        assertNoRestart()
    }

    @Test fun testMixedFloatingOffStopsOrdinarySequenceAndHolds() {
        configureMixed(repeat = true)
        tapPlaybackControl(expectedEnabled = true)
        await("Mixed playback did not start") { target.events.any { it.pointerIds.size == 3 } }
        assertTrue(isPlaybackOn())
        tapPlaybackControl(expectedEnabled = false)
        awaitReleased()
        awaitPlaybackOff()
        assertFalse(runBlocking { SequencePrefsStore.sequenceRunningFlow(activity).first() })
        assertFalse(reservationSnapshot().active)
        assertNoRestart()
    }

    @Test fun testOffDuringPendingPlaybackHandoffCancelsAllPlayback() {
        configureMixed(repeat = false)
        instrumentation.runOnMainSync { TouchPointerOverlay.show(activity) }
        await("The manager did not load") { overlayRoot()?.getSelectedPresetId() == fixtureId }
        sendCommand(SungyoonHelperService.ACTION_START_SEQUENCE)
        await("Playback did not enter input handoff") {
            (serviceField("executionCommand") as? kotlinx.coroutines.Job)?.isActive == true
        }
        instrumentation.runOnMainSync { setPlayback(false) }
        awaitPlaybackOff()
        assertNoRestart()
        assertEquals("An obsolete Play request injected a contact after Off", 0, target.downCount())
    }

    @Test fun testReservationStopCancelsItsPendingInputHandoff() {
        configureMixed(repeat = false)
        instrumentation.runOnMainSync { TouchPointerOverlay.show(activity) }
        await("The manager did not load") { overlayRoot()?.getSelectedPresetId() == fixtureId }
        sendCommand(SungyoonHelperService.ACTION_START_RESERVATION)
        await("Reservation did not enter input handoff") {
            (serviceField("executionCommand") as? kotlinx.coroutines.Job)?.isActive == true
        }
        instrumentation.runOnMainSync { setPlayback(false) }
        await("The pending reservation command did not stop") {
            (serviceField("executionCommand") as? kotlinx.coroutines.Job)?.isActive != true
        }
        SystemClock.sleep(400)
        assertFalse(isHolding())
        assertFalse(runBlocking { ReservationRuntimeStore.snapshotFlow(activity).first().active })
        assertEquals(0, target.downCount())
        awaitPlaybackOff()
    }

    @Test fun testReleasingHoldsBeforeAQueuedTapDoesNotShareTheDownTimestamp() {
        configureMixed(repeat = false)
        runBlocking { withContext(Dispatchers.Main.immediate) {
            assertTrue((serviceField("holdRunner") as HoldGestureRunner).startHolds(
                PresetSession.state.value.points.filter { it.actionType == "hold" }))
        } }
        awaitHoldDown()
        await("Both holds did not reach the target") { target.events.any { it.pointerIds.size == 2 } }
        runBlocking { withContext(Dispatchers.Main.immediate) {
            val runner = serviceField("holdRunner") as HoldGestureRunner
            val tap = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                runner.tap(centerX, centerY + 180f)
            }
            runner.stop()
            assertTrue(withTimeout(5_000) { tap.await() })
        } }
        await("The queued tap did not lift") { target.endCount() == 2 }
        assertEquals(2, target.downCount())
        assertFalse(target.events.any { it.action == MotionEvent.ACTION_CANCEL })
        assertFalse(isHolding())
    }

    @Test fun testPhysicalCancellationDropsOnlyHoldsAndDoesNotReactivateThem() {
        // A host must inject an emulator-console mouse down/up when this status arrives.
        // UiAutomation injection is not hardware input and does not reliably cancel gestures.
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareTouch") == "true")
        configureMixed(repeat = true)
        sendCommand(SungyoonHelperService.ACTION_START_SEQUENCE)
        await("Mixed playback did not start") { target.events.any { it.pointerIds.size == 3 } }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "HARDWARE_TOUCH_READY ${(centerX + 170f).roundToInt()} ${(centerY + 180f).roundToInt()}\n")
        })
        await("Physical touch did not switch holds Off", 15_000) { !isHolding() }
        val end = target.events.size
        await("Ordinary playback did not advance after physical cancellation") {
            target.events.drop(end).any { it.action == MotionEvent.ACTION_DOWN &&
                abs(it.x - centerX) <= 110f &&
                (abs(it.y - (centerY + 150f)) < 2f || abs(it.y - (centerY + 200f)) < 2f) }
        }
        SystemClock.sleep(700)
        assertFalse(isHolding())
        assertTrue(target.events.drop(end).all { it.pointerIds.size == 1 })
        assertTrue("Physical cancellation must leave ordinary playback On", isPlaybackOn())
        sendCommand(SungyoonHelperService.ACTION_STOP_SEQUENCE)
    }

    @Test fun testReservationOffPausesAndOnResumesTheSavedPointWithHolds() {
        configureMixed(repeat = false)
        runBlocking { withContext(Dispatchers.Main.immediate) {
            PresetSession.editSettings(activity, fixtureId, PresetSettings(1_000, 350, 0, false, false))
        } }
        sendCommand(SungyoonHelperService.ACTION_START_RESERVATION) {
            putExtra(SungyoonHelperService.EXTRA_RUN_SEC, 8)
            putExtra(SungyoonHelperService.EXTRA_REST_SEC, 3)
            putExtra(SungyoonHelperService.EXTRA_REPEAT_COUNT, 2)
        }
        await("The first reservation point did not finish") {
            reservationSnapshot().let { it.active && !it.paused && it.nextPointOffset == 1 }
        }
        instrumentation.runOnMainSync { setPlayback(false) }
        await("Off did not pause the reservation") { reservationSnapshot().let { it.active && it.paused } }
        awaitReleased()
        awaitPlaybackOff()
        val paused = reservationSnapshot()
        assertEquals(ReservationRuntimeStore.PHASE_RUN, paused.phase)
        assertEquals("Off must retain the next ordinary point", 1, paused.nextPointOffset)
        assertTrue(paused.pausedRemainingMs in 1L..8_000L)
        SystemClock.sleep(300)
        assertEquals("Paused reservation progress changed while Off", paused, reservationSnapshot())

        val resumedEvents = target.events.size
        tapPlaybackControl(expectedEnabled = true)
        await("On did not resume the reservation and holds") {
            isHolding() && reservationSnapshot().let { it.active && !it.paused }
        }
        await("The saved next drag did not start") {
            target.events.drop(resumedEvents).any {
                it.action == MotionEvent.ACTION_POINTER_DOWN && it.pointerIds.size == 3
            }
        }
        val firstOrdinary = target.events.drop(resumedEvents).first {
            it.action == MotionEvent.ACTION_POINTER_DOWN && it.pointerIds.size == 3
        }.let { it.coordinates.getValue(it.actionId) }
        assertEquals("Resume replayed the earlier tap", (centerY + 200f).roundToInt().toFloat(), firstOrdinary.second, 0f)
        assertEquals((centerX - 100f).roundToInt().toFloat(), firstOrdinary.first, 0f)
        assertEquals(paused.cycleCurrent, reservationSnapshot().cycleCurrent)
        assertTrue(isPlaybackOn())
        instrumentation.runOnMainSync { setPlayback(false) }
        awaitReleased()
        awaitPlaybackOff()
    }

    @Test fun testReservationRestAndToolbarPauseResumeWithGlobalToggle() {
        configureMixed(repeat = false)
        sendCommand(SungyoonHelperService.ACTION_START_RESERVATION) {
            putExtra(SungyoonHelperService.EXTRA_RUN_SEC, 2)
            putExtra(SungyoonHelperService.EXTRA_REST_SEC, 2)
            putExtra(SungyoonHelperService.EXTRA_REPEAT_COUNT, 2)
        }
        await("The new reservation did not start") {
            runBlocking { ReservationRuntimeStore.snapshotFlow(activity).first().let {
                it.active && it.phase == ReservationRuntimeStore.PHASE_RUN
            } }
        }
        await("Reservation did not enter rest", 12_000) {
            runBlocking { ReservationRuntimeStore.snapshotFlow(activity).first().phase == ReservationRuntimeStore.PHASE_REST }
        }
        assertTrue(isHolding())
        sendCommand(SungyoonHelperService.ACTION_PAUSE_RESERVATION)
        await("Reservation did not pause") { runBlocking { ReservationRuntimeStore.snapshotFlow(activity).first().paused } }
        assertTrue(isHolding())
        assertTrue("Toolbar pause retains H, so the global control remains On", isPlaybackOn())
        instrumentation.runOnMainSync { setPlayback(false) }
        awaitReleased()
        awaitPlaybackOff()
        val paused = reservationSnapshot()
        assertTrue(paused.active && paused.paused)
        assertEquals(ReservationRuntimeStore.PHASE_REST, paused.phase)
        assertTrue(paused.pausedRemainingMs > 0)
        SystemClock.sleep(300)
        assertEquals("Off must freeze the remaining rest time and point offset", paused, reservationSnapshot())

        tapPlaybackControl(expectedEnabled = true)
        await("On did not restore H while resuming the remaining rest") {
            isHolding() && reservationSnapshot().let {
                it.active && !it.paused && it.phase == ReservationRuntimeStore.PHASE_REST
            }
        }
        assertEquals(paused.nextPointOffset, reservationSnapshot().nextPointOffset)
        assertEquals(paused.cycleCurrent, reservationSnapshot().cycleCurrent)
        assertTrue(isPlaybackOn())

        // A new On must wait for an immediately preceding Off to finish its pause/release.
        instrumentation.runOnMainSync { setPlayback(false); setPlayback(true) }
        await("Rapid Off/On lost the active reservation or H") {
            isHolding() && isPlaybackOn() && reservationSnapshot().let { it.active && !it.paused }
        }
        SystemClock.sleep(300)
        assertTrue("Old Off cleanup cleared the newer On state", isPlaybackOn())
        assertTrue("Old Off cleanup released the newer holds", isHolding())
        assertFalse("Old Off cleanup overwrote the resumed reservation", reservationSnapshot().paused)
        instrumentation.runOnMainSync { setPlayback(false) }
        awaitReleased()
        awaitPlaybackOff()
        sendCommand(SungyoonHelperService.ACTION_STOP_RESERVATION)
    }

    @Test fun testNineHoldsLeaveRoomForOneOrdinaryContact() {
        runBlocking { withContext(Dispatchers.Main.immediate) {
            PresetSession.editPoints(activity, fixtureId) {
                (0 until 9).map { n -> HighlightingPoint("h$n", centerX + (n % 3 - 1) * 60,
                    centerY + (n / 3 - 1) * 60, n, 100, "hold") } +
                    HighlightingPoint("tenth-contact", centerX, centerY + 220, 9, 100)
            }
        } }
        sendCommand(SungyoonHelperService.ACTION_START_SEQUENCE)
        await("Nine holds plus the tap were not injected") { target.events.any { it.pointerIds.size == 10 } }
        await("The tenth contact did not lift") { target.events.any { it.action == MotionEvent.ACTION_POINTER_UP } }
        assertTrue(isHolding())
        assertFalse(target.events.any { it.action == MotionEvent.ACTION_CANCEL })
        instrumentation.runOnMainSync { setPlayback(false) }
        awaitReleased()
    }

    @Test fun testManagerAddsHoldsLabelsActionsSeparatelyAndEnforcesLimit() {
        configureMixed(repeat = false)
        instrumentation.runOnMainSync { TouchPointerOverlay.show(activity) }
        await("Mixed manager did not load") { overlayRoot()?.getSelectedPresetId() == fixtureId }
        var root = overlayRoot()!!
        var controls = root.javaClass.getDeclaredField("controls").apply { isAccessible = true }
            .get(root) as com.sungyoon.helper.overlay.pointer.PointerOverlayControlsViews
        @Suppress("UNCHECKED_CAST")
        val views = root.javaClass.getDeclaredField("views").apply { isAccessible = true }.get(root) as Map<String, View>
        fun label(id: String): String = views.getValue(id).javaClass.getDeclaredField("label")
            .apply { isAccessible = true }.get(views.getValue(id)) as String
        instrumentation.runOnMainSync {
            assertEquals("H1", label(holdId))
            assertEquals("H2", label("integration-h2"))
            assertEquals("1", label("integration-tap"))
            assertEquals("2", label("integration-drag"))
            assertTrue(controls.addBtn.isEnabled && controls.addDragBtn.isEnabled && controls.addHoldBtn.isEnabled)
            assertTrue(controls.clearAllBtn.isEnabled && controls.playToggleBtn.isEnabled && controls.reserveBtn.isEnabled)
            repeat(8) { controls.addHoldBtn.performClick() }
        }
        await("Hold creation did not reach its limit") { PresetSession.state.value.points.count { it.actionType == "hold" } == 9 }
        runBlocking { withContext(Dispatchers.Main.immediate) { TouchPointerOverlay.flushEdits() } }
        assertEquals(11, PresetSession.state.value.points.size)
        assertEquals(9, readSavedHoldCount(activity, fixtureId))
        instrumentation.runOnMainSync { root.setControlPanelVisibleFromController(true) }
        SystemClock.sleep(250)
        automation.takeScreenshot()?.let { bitmap ->
            java.io.File(activity.cacheDir, "mixed-hold-manager.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        await("The activity did not recreate in landscape") { activity.isDestroyed }
        SystemClock.sleep(300)
        instrumentation.runOnMainSync { TouchPointerOverlay.show(instrumentation.targetContext) }
        await("The manager did not load in landscape") {
            overlayRoot()?.let { it.width > it.height && it.getSelectedPresetId() == fixtureId } == true
        }
        root = overlayRoot()!!
        controls = root.javaClass.getDeclaredField("controls").apply { isAccessible = true }
            .get(root) as com.sungyoon.helper.overlay.pointer.PointerOverlayControlsViews
        instrumentation.runOnMainSync {
            root.setControlPanelVisibleFromController(true)
            val scroll = root.javaClass.getDeclaredField("controlPanelScrollHost").apply { isAccessible = true }
                .get(root) as android.widget.ScrollView
            scroll.fullScroll(View.FOCUS_DOWN)
        }
        SystemClock.sleep(300)
        automation.takeScreenshot()?.let { bitmap ->
            java.io.File(activity.cacheDir, "mixed-hold-manager-landscape.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        instrumentation.runOnMainSync {
            val rect = android.graphics.Rect()
            assertTrue("The hold addition button is unreachable in landscape", controls.addHoldBtn.getGlobalVisibleRect(rect))
            assertEquals(controls.addHoldBtn.height, rect.height())
            assertTrue("Playback is unreachable in landscape", controls.playToggleBtn.getGlobalVisibleRect(rect))
            assertEquals(controls.playToggleBtn.height, rect.height())
        }
        instrumentation.runOnMainSync { controls.clearAllBtn.performClick() }
        await("Clear all did not remove every pointer type") { PresetSession.state.value.points.isEmpty() }
        assertTrue(readSavedHoldCount(activity, fixtureId) == 0)
        await("An empty preset must retain a visible, disabled playback control") {
            var disabled = false
            instrumentation.runOnMainSync { disabled = playbackButton().isShown && !playbackButton().isEnabled }
            disabled
        }
    }

    private fun readSavedHoldCount(context: Context, id: String) = runBlocking {
        PresetStore.read(context).entries.single { it.id == id }.points.count { it.actionType == "hold" }
    }

    @Test fun testAuxiliaryHandleMovesOnlySelectedHoldWithoutAxisSnapping() {
        configureMixed(repeat = false)
        instrumentation.runOnMainSync { TouchPointerOverlay.show(activity) }
        await("Mixed manager did not load") { overlayRoot()?.getSelectedPresetId() == fixtureId }
        val root = overlayRoot()!!
        val before = PresetSession.state.value.points
        instrumentation.runOnMainSync {
            root.setControlPanelVisibleFromController(false)
            root.javaClass.getDeclaredMethod("selectPointer", String::class.java, PointerOverlayRootView.Endpoint::class.java)
                .apply { isAccessible = true }.invoke(root, holdId, PointerOverlayRootView.Endpoint.START)
        }
        val handle = root.javaClass.getDeclaredField("moveStickHandle").apply { isAccessible = true }.get(root) as View
        await("The auxiliary handle is not visible") { handle.isShown }
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            listOf(Triple(MotionEvent.ACTION_DOWN, 10f, 10f), Triple(MotionEvent.ACTION_MOVE, 75f, 48f),
                Triple(MotionEvent.ACTION_UP, 75f, 48f)).forEach { (action, x, y) ->
                val event = MotionEvent.obtain(now, now + 20, action, x, y, 0)
                try { handle.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        await("The auxiliary hold move was not saved") { PresetSession.state.value.points.first { it.id == holdId }.x != before.first().x }
        val after = PresetSession.state.value.points
        assertEquals(before.filterNot { it.id == holdId }, after.filterNot { it.id == holdId })
        val moved = after.single { it.id == holdId }
        assertEquals(before.first().x + 65f, moved.x, 0.01f)
        assertEquals(before.first().y + 38f, moved.y, 0.01f)
        assertEquals("hold", moved.actionType)
    }

    private fun configureMixed(repeat: Boolean) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            PresetSession.editPoints(activity, fixtureId) {
                listOf(
                    HighlightingPoint(holdId, centerX - 80, centerY, 7, 100, "hold"),
                    HighlightingPoint("integration-h2", centerX + 80, centerY, 20, 100, "hold"),
                    HighlightingPoint("integration-tap", centerX, centerY + 150, 2, 100),
                    HighlightingPoint("integration-drag", centerX - 100, centerY + 200, 9, 100,
                        "drag", centerX + 100, centerY + 200, 350))
            }
            PresetSession.editSettings(activity, fixtureId, PresetSettings(100, 350, 0, repeat, false))
        }
    }

    private fun sendCommand(action: String, extras: Intent.() -> Unit = {}) {
        activity.sendBroadcast(Intent(action).apply { setPackage(activity.packageName); extras() })
    }

    private fun startAndAwaitDown() {
        instrumentation.runOnMainSync { setPlayback(true) }
        awaitHoldDown()
    }

    private fun awaitHoldDown() {
        try {
            await("The service did not inject a hold DOWN event") { target.downCount() >= 1 }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}; running=${isHolding()}, manager=${TouchPointerOverlay.isShowing()}, " +
                "preset=${PresetSession.state.value.activeId}, ready=${PresetSession.state.value.ready}, events=${target.events}", failure)
        }
        assertTrue(isHolding())
    }

    private fun awaitReleased() {
        await("The hold did not release its pointer") {
            !isHolding() && target.endCount() >= target.downCount()
        }
    }

    private fun assertNoRestart() {
        val downCount = target.downCount()
        SystemClock.sleep(450)
        assertFalse(isHolding())
        assertFalse("The global control still shows On", isPlaybackOn())
        assertEquals("A stale callback restarted the hold", downCount, target.downCount())
    }

    private fun playbackButton(): TextView {
        val controller = serviceField("floatingToggle")!!
        return controller.javaClass.getDeclaredField("playbackButton").apply { isAccessible = true }
            .get(controller) as TextView
    }

    private fun isPlaybackOn(): Boolean {
        var on = false
        instrumentation.runOnMainSync {
            val controller = serviceField("floatingToggle")
            val button = controller?.javaClass?.getDeclaredField("playbackButton")
                ?.apply { isAccessible = true }?.get(controller) as? TextView
            on = button?.text?.toString() == "On"
        }
        return on
    }

    private fun awaitPlaybackOff() {
        await("Playback did not finish its Off transition") {
            !isPlaybackOn() && !isHolding() &&
                (serviceField("executionCommand") as? kotlinx.coroutines.Job)?.isActive != true &&
                (serviceField("runnerJob") as? kotlinx.coroutines.Job)?.isActive != true &&
                (serviceField("reservationJob") as? kotlinx.coroutines.Job)?.isActive != true
        }
    }

    private fun reservationSnapshot() = runBlocking { ReservationRuntimeStore.snapshotFlow(activity).first() }

    private fun setPlayback(enabled: Boolean) {
        SungyoonHelperService::class.java.getDeclaredMethod("setPlaybackEnabled", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(service, enabled)
        }
    }

    private fun isHolding(): Boolean {
        var running = false
        instrumentation.runOnMainSync { running = (serviceField("holdRunner") as? HoldGestureRunner)?.isRunning == true }
        return running
    }

    private fun serviceField(name: String): Any? = SungyoonHelperService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(service)

    private fun overlayRoot(): PointerOverlayRootView? {
        val controller = TouchPointerOverlay::class.java.getDeclaredField("controller")
            .apply { isAccessible = true }.get(TouchPointerOverlay) ?: return null
        return controller.javaClass.getDeclaredField("root")
            .apply { isAccessible = true }.get(controller) as? PointerOverlayRootView
    }

    private fun connectedService(): SungyoonHelperService? {
        var connected: SungyoonHelperService? = null
        instrumentation.runOnMainSync {
            connected = SungyoonHelperService::class.java.getDeclaredField("connectedService")
                .apply { isAccessible = true }.get(null) as? SungyoonHelperService
        }
        return connected
    }

    private fun injectTap(x: Float, y: Float, throughAccessibilityFilter: Boolean = false) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 30, MotionEvent.ACTION_UP, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            // Use the filter for overlay input handoff. Cancellation is separately verified
            // with host-generated hardware input, not this instrumentation test hook.
            val filterInjection = if (throughAccessibilityFilter) UiAutomation::class.java
                .getMethod("injectInputEventToInputFilter", InputEvent::class.java) else null
            if (filterInjection != null) filterInjection.invoke(automation, down)
            else assertTrue(automation.injectInputEvent(down, true))
            SystemClock.sleep(30)
            if (filterInjection != null) filterInjection.invoke(automation, up)
            else assertTrue(automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun await(message: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue(message, condition())
    }

    private data class TouchSample(
        val action: Int,
        val time: Long,
        val x: Float,
        val y: Float,
        val managerVisible: Boolean,
        val pointerIds: List<Int>,
        val actionId: Int,
        val coordinates: Map<Int, Pair<Float, Float>>
    )

    private class RecordingView(context: Context) : View(context) {
        val events = CopyOnWriteArrayList<TouchSample>()

        init {
            setBackgroundColor(Color.rgb(30, 36, 45))
            isClickable = true
        }

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            // This raw-input target has no click action; exercise gesture fallback explicitly.
            info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val ids = (0 until event.pointerCount).map { event.getPointerId(it) }
            val offsetX = event.rawX - event.x
            val offsetY = event.rawY - event.y
            events.add(TouchSample(event.actionMasked, event.eventTime, event.rawX, event.rawY,
                TouchPointerOverlay.isShowing(), ids, event.getPointerId(event.actionIndex),
                (0 until event.pointerCount).associate { event.getPointerId(it) to
                    (event.getX(it) + offsetX to event.getY(it) + offsetY) }))
            return true
        }

        fun downCount() = events.count { it.action == MotionEvent.ACTION_DOWN }
        fun endCount() = events.count { it.action == MotionEvent.ACTION_UP || it.action == MotionEvent.ACTION_CANCEL }
    }
}
