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
import androidx.test.platform.app.InstrumentationRegistry
import com.sungyoon.helper.data.PresetSession
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.overlay.floating.FloatingToggleOverlayController
import com.sungyoon.helper.overlay.pointer.PointerOverlayRootView
import com.sungyoon.helper.service.HoldGestureRunner
import com.sungyoon.helper.service.HoldGestureBackend
import com.sungyoon.helper.service.HoldStroke
import kotlinx.coroutines.Dispatchers
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
        runBlocking {
            withTimeout(10_000) {
                withContext(Dispatchers.Main.immediate) {
                    SungyoonHelperService.requestHoldStop()
                    SungyoonHelperService.awaitHoldStopped()
                    TouchPointerOverlay.flushAndHide()
                    PresetSession.initialize(activity)
                    PresetSession.activate(activity, PresetEntry.HOLD_PRESET_ID, centerX, centerY)
                    PresetSession.editPoints(activity, PresetEntry.HOLD_PRESET_ID) { points ->
                        points.map { it.copy(x = centerX, y = centerY, dragToX = centerX, dragToY = centerY) }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        // Remove any residual input from an interrupted diagnostic run on this disposable AVD.
        injectTap(centerX, centerY)
        target.events.clear()
    }

    @After
    fun tearDown() {
        if (!isolatedDevice) return
        try {
            if (::service.isInitialized) {
                instrumentation.runOnMainSync { setHold(false) }
                runBlocking {
                    withTimeout(5_000) {
                        withContext(Dispatchers.Main.immediate) { SungyoonHelperService.awaitHoldStopped() }
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

        instrumentation.runOnMainSync { setHold(false) }
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
        startAndAwaitDown()
        repeat(8) {
            instrumentation.runOnMainSync {
                setHold(false)
                setHold(true)
            }
            SystemClock.sleep(20)
            instrumentation.runOnMainSync { setHold(false) }
        }
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
        assertNoRestart()
    }

    @Test
    fun testFloatingOffDoesNotRestartAfterPhysicalTouchCancellation() {
        startAndAwaitDown()
        val controller = serviceField("floatingToggle")!!
        val button = controller.javaClass.getDeclaredField("holdButton").apply { isAccessible = true }.get(controller) as View
        val callbackField = controller.javaClass.getDeclaredField("onHoldToggle").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val callback = callbackField.get(controller) as (Boolean) -> Unit
        val requested = AtomicReference<Boolean?>()
        await("The floating hold control has not been laid out") {
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
            await("The physical tap did not activate the floating hold control at ${point.toList()}") { requested.get() != null }
            assertEquals("A cancellation callback changed the intended Off click into On", false, requested.get())
            awaitReleased()
            assertNoRestart()
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
                overlayRoot()?.getSelectedPresetId() == PresetEntry.HOLD_PRESET_ID
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

    private fun startAndAwaitDown() {
        instrumentation.runOnMainSync { setHold(true) }
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
        assertEquals("A stale callback restarted the hold", downCount, target.downCount())
    }

    private fun setHold(enabled: Boolean) {
        SungyoonHelperService::class.java.getDeclaredMethod("setHoldEnabled", Boolean::class.javaPrimitiveType).apply {
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
            // The normal injection API skips this filter and cannot simulate a physical touch
            // cancelling an accessibility gesture. The platform test API enters the real filter.
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
        val managerVisible: Boolean
    )

    private class RecordingView(context: Context) : View(context) {
        val events = CopyOnWriteArrayList<TouchSample>()

        init {
            setBackgroundColor(Color.rgb(30, 36, 45))
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) events.add(TouchSample(event.actionMasked, event.eventTime, event.rawX, event.rawY, TouchPointerOverlay.isShowing()))
            return true
        }

        fun downCount() = events.count { it.action == MotionEvent.ACTION_DOWN }
        fun endCount() = events.count { it.action == MotionEvent.ACTION_UP || it.action == MotionEvent.ACTION_CANCEL }
    }
}
