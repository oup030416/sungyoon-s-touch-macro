package com.sungyoon.helper.overlay.pointer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.sungyoon.helper.R
import com.sungyoon.helper.SungyoonHelperService
import com.sungyoon.helper.core.permissions.isOverlayGranted
import com.sungyoon.helper.core.permissions.isServiceEnabled
import com.sungyoon.helper.core.permissions.openAccessibilitySettings
import com.sungyoon.helper.core.permissions.openOverlaySettings
import com.sungyoon.helper.data.PointsStore
import com.sungyoon.helper.data.PresetStore
import com.sungyoon.helper.data.PresetSession
import com.sungyoon.helper.model.PresetSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.sungyoon.helper.data.ReservationPrefsStore
import com.sungyoon.helper.data.ReservationRuntimeStore
import com.sungyoon.helper.data.SequencePrefsStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_DRAG
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_HOLD
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_TAP
import com.sungyoon.helper.model.PresetEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PointerOverlayController(private val app: Context) {

    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: PointerOverlayRootView? = null
    private var added = false

    private var collectJob: Job? = null
    private var prefsJob: Job? = null
    private val commandMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingWindowRemoval: CompletableDeferred<Unit>? = null
    private var settingsPersistJob: Job? = null
    private var pendingSettings: Pair<String?, PresetSettings>? = null
    private var collectStartFallbackJob: Job? = null

    private var latestPoints: List<HighlightingPoint> = emptyList()
    private var latestPresets: List<PresetEntry> = emptyList()
    private val draggingIds = HashSet<String>()
    private val dragOwners = HashMap<String, String?>()
    private var collectStarted: Boolean = false

    private var sequenceRunning: Boolean = false
    private var repeatEnabled: Boolean = true
    private var tapIntervalMs: Long = 1000L
    private var dragDurationMs: Long = 1000L
    private var randomTouchRadiusDp: Int = 5
    private var touchAnimEnabled: Boolean = true

    private var stateReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    private var overlayLp: WindowManager.LayoutParams? = null
    private val minDragDurationMs = 100L
    private val maxDragDurationMs = 10_000L
    private val minRandomRadiusDp = 0
    private val maxRandomRadiusDp = 20

    fun isShowing(): Boolean = added

    fun show(forceOpenControlPanel: Boolean = false) {
        try {
            app.sendBroadcast(
                Intent(SungyoonHelperService.ACTION_PAUSE_RESERVATION).apply {
                    setPackage(app.packageName)
                }
            )
        } catch (_: Throwable) {}

        SungyoonHelperService.requestHoldStop()
        requestStopSequence()
        sequenceRunning = false
        if (added) return

        if (!isOverlayGranted(app)) {
            app.openOverlaySettings()
            toast(app.getString(R.string.toast_overlay_required))
            return
        }

        val v = PointerOverlayRootView(app).apply {

            setOnAddClick { performAddPointer() }
            setOnAddDragClick { performAddDragPointer() }
            setOnAddHoldClick { addPointer(ACTION_TYPE_HOLD) }

            // ✅ 추가: 패널 표시 상태가 바뀔 때마다 Store에 저장
            setOnControlPanelVisibleChanged { visible ->
                scope.launch { SequencePrefsStore.setPointerPanelVisible(app, visible) }
            }
            setOnReservationPanelVisibleChanged { visible ->
                scope.launch { SequencePrefsStore.setReservationPanelVisible(app, visible) }
            }
            setOnPresetPanelVisibleChanged { visible ->
                scope.launch { SequencePrefsStore.setPresetPanelVisible(app, visible) }
            }

            setOnReservationStartClick { runSec, restSec, repeatCount ->
                launchCommand {
                    flushPendingLocked()
                    if (!PresetSession.state.value.ready) return@launchCommand
                    val active = ReservationRuntimeStore.activeFlow(app).first()
                    val paused = ReservationRuntimeStore.pausedFlow(app).first()
                    if (active) {
                        if (paused)
                        {
                            app.sendBroadcast(Intent(SungyoonHelperService.ACTION_RESUME_RESERVATION).apply {
                                setPackage(app.packageName)
                                putExtra(SungyoonHelperService.EXTRA_MANUAL_RESUME, true)
                            })
                            toast(app.getString(R.string.toast_reservation_resumed))
                            hide()
                            return@launchCommand
                        }

                        toast(app.getString(R.string.reservation_already_running))
                        return@launchCommand
                    }

                    // ✅ 초 단위 저장
                    ReservationPrefsStore.setRunSeconds(app, runSec)
                    ReservationPrefsStore.setRestSeconds(app, restSec)
                    ReservationPrefsStore.setRepeatCount(app, repeatCount)

                    SequencePrefsStore.setPointerPanelVisible(app, true)
                    SequencePrefsStore.setReservationPanelVisible(app, true)
                    SequencePrefsStore.setPresetPanelVisible(app, false)


                    if (!isServiceEnabled(app)) {
                        app.openAccessibilitySettings()
                        toast(app.getString(R.string.toast_accessibility_required))
                        return@launchCommand
                    }

                    ensurePointsLoaded()
                    if (latestPoints.isEmpty()) {
                        toast(app.getString(R.string.toast_points_required))
                        return@launchCommand
                    }

                    // ✅ 서비스로 초 단위 전달 (새 Extra 사용)
                    app.sendBroadcast(Intent(SungyoonHelperService.ACTION_START_RESERVATION).apply {
                        setPackage(app.packageName)
                        putExtra(SungyoonHelperService.EXTRA_RUN_SEC, runSec)
                        putExtra(SungyoonHelperService.EXTRA_REST_SEC, restSec)
                        putExtra(SungyoonHelperService.EXTRA_REPEAT_COUNT, repeatCount)
                    })

                    toast(app.getString(R.string.toast_reservation_started))
                    hide()
                }
            }




            setOnReserveClick {
                openReservationPanel()
                scope.launch {
                    root?.let { loadReservationPrefsInto(it) }
                    val runSec = ReservationPrefsStore.runSecondsFlow(app).first()
                    val restSec = ReservationPrefsStore.restSecondsFlow(app).first()
                    val repeatCount = ReservationPrefsStore.repeatCountFlow(app).first()
                    setReservationValuesFromStore(runSec, restSec, repeatCount)
                }
            }

            setOnPresetListClick {
                openPresetPanel()
            }

            setOnClearAllClick {
                val sourceId = PresetSession.state.value.activeId
                launchCommand {
                    PresetSession.editPoints(app, sourceId) { emptyList() }
                    toast(app.getString(R.string.toast_clear_all_done))
                }
            }

            setOnPresetAddCurrentClick {
                launchCommand {
                    flushPendingLocked()
                    PresetSession.addCurrent(app)
                    toast(app.getString(R.string.preset_add_saved))
                }
            }

            setOnPresetRenameClick { preset ->
                showInputDialog(
                    title = app.getString(R.string.preset_rename_title),
                    initialValue = preset.name,
                    hint = app.getString(R.string.preset_name_hint),
                    confirmText = app.getString(R.string.dialog_save),
                    cancelText = app.getString(R.string.dialog_cancel)
                ) { nextName ->
                    launchCommand {
                        PresetSession.rename(app, preset.id, nextName)
                        toast(app.getString(R.string.preset_renamed))
                    }
                }
            }

            setOnPresetDeleteClick { presetId ->
                val preset = latestPresets.firstOrNull { it.id == presetId } ?: return@setOnPresetDeleteClick
                showConfirmationDialog(
                    title = app.getString(R.string.preset_delete_title),
                    message = app.getString(R.string.preset_delete_message),
                    confirmText = app.getString(R.string.dialog_delete),
                    cancelText = app.getString(R.string.dialog_cancel),
                    destructive = true
                ) {
                    launchCommand {
                        flushPendingLocked()
                        PresetSession.delete(app, preset.id)
                        toast(app.getString(R.string.preset_deleted))
                    }
                }
            }

            setOnPresetLoadClick { presetId ->
                if (PresetSession.state.value.activeId != presetId) {
                    launchCommand {
                        flushPendingLocked()
                        PresetSession.activate(app, presetId)
                    }
                }
            }

            setOnCloseClick { hide() }

            setOnPlayToggleClick { onPlayToggleClicked() }

            setOnRepeatToggleClick {
                repeatEnabled = !repeatEnabled
                setRepeatEnabled(repeatEnabled)
                queueSettings()
            }
            setOnTouchAnimationToggleClick {
                touchAnimEnabled = !touchAnimEnabled
                setTouchAnimationEnabled(touchAnimEnabled)
                queueSettings()
            }
            setOnTapIntervalChanged { seconds ->
                tapIntervalMs = ((seconds * 1000f) + 0.5f).toLong().coerceAtLeast(100L)
                queueSettings()
            }
            setOnDragDurationChanged { seconds ->
                dragDurationMs = secondsToMs(seconds)
                queueSettings()
            }
            setOnRandomTouchRadiusChanged { radiusDp ->
                randomTouchRadiusDp = clampRandomRadiusDp(radiusDp)
                queueSettings()
            }

            setOnRequestIme { enable ->
                setOverlayFocusableForIme(enable)
            }

            setOnDeletePointClick { id ->
                val sourceId = PresetSession.state.value.activeId
                launchCommand {
                    PresetSession.editPoints(app, sourceId) { points -> points.filterNot { it.id == id } }
                    toast(app.getString(R.string.toast_pointer_deleted))
                }
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(v, lp)
            root = v
            added = true
            overlayLp = lp
        } catch (t: Throwable) {
            root = null
            added = false
            overlayLp = null
            toast(app.getString(R.string.toast_overlay_failed, t.javaClass.simpleName))
            return
        }

        registerSequenceStateReceiver()
        startPrefsCollect()
        collectStarted = false

        val rootView = root ?: return
        rootView.setEditingState(ready = false)
        rootView.doOnLayout { initializeAfterLayout() }
        collectStartFallbackJob?.cancel()
        collectStartFallbackJob = scope.launch {
            delay(500L)
            initializeAfterLayout()
        }

        scope.launch {
            val panelVisiblePref = SequencePrefsStore.pointerPanelVisibleFlow(app).first()
            val reservationVisiblePref = SequencePrefsStore.reservationPanelVisibleFlow(app).first()
            val presetVisiblePref = SequencePrefsStore.presetPanelVisibleFlow(app).first()

            // ✅ 같은 스코프에서 바로 사용
            val rv = root ?: return@launch
            rv.post {
                val v = root ?: return@post

                val effectivePanelVisible = if (forceOpenControlPanel) {
                    true
                } else if (reservationVisiblePref || presetVisiblePref) {
                    true
                } else {
                    panelVisiblePref
                }
                v.setControlPanelVisibleFromController(effectivePanelVisible)

                if (presetVisiblePref) {
                    v.openPresetPanel()
                    v.closeReservationPanel()
                } else if (reservationVisiblePref) {
                    v.openReservationPanel()
                    scope.launch { loadReservationPrefsInto(v) } // ✅ 예약값 복원 주입
                } else {
                    v.closeReservationPanel()
                    v.closePresetPanel()
                }
            }
        }
    }

    fun hide() {
        launchCommand {
            try { flushPendingLocked() } finally { hideNow() }
        }
    }

    suspend fun flushEdits() = commandMutex.withLock { flushPendingLocked() }

    suspend fun flushAndHide() = commandMutex.withLock {
        flushPendingLocked()
        hideNow()
        pendingWindowRemoval?.await()
    }

    private fun hideNow() {

        root?.let { v ->
            val panelVisibleNow = v.isControlPanelVisible()
            val reservationVisibleNow = v.isReservationPanelVisible()
            val presetVisibleNow = v.isPresetPanelVisible()
            scope.launch {
                SequencePrefsStore.setPointerPanelVisible(app, panelVisibleNow)
                SequencePrefsStore.setReservationPanelVisible(app, reservationVisibleNow)
                SequencePrefsStore.setPresetPanelVisible(app, presetVisibleNow)
            }
        }


        collectJob?.cancel()
        collectJob = null
        collectStarted = false
        collectStartFallbackJob?.cancel()
        collectStartFallbackJob = null
        prefsJob?.cancel()
        prefsJob = null
        settingsPersistJob?.cancel()
        settingsPersistJob = null
        unregisterSequenceStateReceiver()
        if (!added) return
        root?.let { closingView ->
            val removed = CompletableDeferred<Unit>()
            pendingWindowRemoval = removed
            val closingParams = WindowManager.LayoutParams().apply {
                copyFrom(checkNotNull(overlayLp))
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                alpha = 0f
            }
            val finishRemoval = Runnable {
                // WindowManager publishes input routing in subsequent animation transactions.
                val frames = Choreographer.getInstance()
                frames.postFrameCallback { frames.postFrameCallback {
                    if (pendingWindowRemoval === removed) pendingWindowRemoval = null
                    removed.complete(Unit)
                } }
            }
            var removalStarted = false
            lateinit var beforeDraw: ViewTreeObserver.OnPreDrawListener
            lateinit var removalTimeout: Runnable
            val removeWindow = Runnable {
                if (removalStarted) return@Runnable
                removalStarted = true
                mainHandler.removeCallbacks(removalTimeout)
                if (closingView.viewTreeObserver.isAlive) {
                    closingView.viewTreeObserver.removeOnPreDrawListener(beforeDraw)
                }
                try {
                    wm.removeView(closingView)
                    if (!closingView.isAttachedToWindow) mainHandler.post(finishRemoval)
                } catch (error: RuntimeException) {
                    removed.completeExceptionally(error)
                }
            }
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    view.removeOnAttachStateChangeListener(this)
                    mainHandler.removeCallbacks(removalTimeout)
                    mainHandler.removeCallbacks(removeWindow)
                    if (view.viewTreeObserver.isAlive) {
                        view.viewTreeObserver.removeOnPreDrawListener(beforeDraw)
                    }
                    // Detach callbacks precede the window/input-channel teardown in ViewRootImpl.
                    mainHandler.post(finishRemoval)
                }
            }
            closingView.addOnAttachStateChangeListener(listener)
            beforeDraw = ViewTreeObserver.OnPreDrawListener {
                closingView.viewTreeObserver.removeOnPreDrawListener(beforeDraw)
                mainHandler.removeCallbacks(removalTimeout)
                // Relayout has submitted alpha/flags before pre-draw. Keep the input channel alive
                // while that update propagates; removing it first can discard the hold's only DOWN.
                mainHandler.postDelayed(removeWindow, 100L)
                true
            }
            removalTimeout = Runnable {
                // A stopped display may never draw. Clean up, but never start a hold blindly.
                removed.completeExceptionally(IllegalStateException("Overlay input handoff did not draw"))
                removeWindow.run()
            }
            try {
                closingView.viewTreeObserver.addOnPreDrawListener(beforeDraw)
                wm.updateViewLayout(closingView, closingParams)
                mainHandler.postDelayed(removalTimeout, 1_000L)
            } catch (error: RuntimeException) {
                removed.completeExceptionally(error)
                removeWindow.run()
            }
        }
        root = null
        added = false
        overlayLp = null
        draggingIds.clear()
        dragOwners.clear()
    }

    private fun onPlayToggleClicked() {
        val v = root ?: return

        if (sequenceRunning) {
            app.sendBroadcast(
                Intent(SungyoonHelperService.ACTION_STOP_SEQUENCE).apply {
                    setPackage(app.packageName)
                }
            )
            v.setSequenceRunning(false)
            sequenceRunning = false
            toast(app.getString(R.string.toast_sequence_stopped))
            return
        }

        if (!isServiceEnabled(app)) {
            app.openAccessibilitySettings()
            toast(app.getString(R.string.toast_accessibility_required))
            return
        }

        launchCommand {
            flushPendingLocked()
            if (!PresetSession.state.value.ready) return@launchCommand
            ensurePointsLoaded()
            if (latestPoints.isEmpty()) {
                toast(app.getString(R.string.toast_points_required))
                return@launchCommand
            }

            app.sendBroadcast(
                Intent(SungyoonHelperService.ACTION_START_SEQUENCE).apply {
                    setPackage(app.packageName)
                }
            )
            toast(
                app.getString(
                    if (repeatEnabled) R.string.toast_sequence_repeat_started
                    else R.string.toast_sequence_started
                )
            )
            hide()
        }
    }

    private fun startCollect() {
        val v = root ?: return
        collectJob?.cancel()
        collectJob = scope.launch {
            PresetSession.state.collectLatest { state ->
                v.setEditingState(state.ready)
                if (!state.ready) {
                    v.setSelectedPresetId(null)
                    return@collectLatest
                }
                val points = state.points
                latestPoints = points
                latestPresets = state.entries
                v.setPresetEntries(state.entries)
                v.setSelectedPresetId(state.activeId)
                val settings = pendingSettings?.takeIf { it.first == state.activeId }?.second ?: state.settings
                tapIntervalMs = settings.tapIntervalMs
                dragDurationMs = settings.dragDurationMs
                randomTouchRadiusDp = settings.randomRadiusDp
                repeatEnabled = settings.repeatEnabled
                touchAnimEnabled = settings.touchAnimationEnabled
                v.setTapIntervalSeconds(tapIntervalMs / 1000f)
                v.setDragDurationSeconds(dragDurationMs / 1000f)
                v.setRandomTouchRadiusDp(randomTouchRadiusDp)
                v.setRepeatEnabled(repeatEnabled)
                v.setTouchAnimationEnabled(touchAnimEnabled)
                val sorted = points.sortedBy { it.index }
                val labelMap = HashMap<String, String>(sorted.size)
                var holdNumber = 0
                var actionNumber = 0
                sorted.forEach { p ->
                    labelMap[p.id] = if (p.actionType == ACTION_TYPE_HOLD)
                        app.getString(R.string.hold_pointer_label, ++holdNumber) else "${++actionNumber}"
                }

                v.syncPoints(
                    points = points,
                    labelProvider = { id, endpoint ->
                        val base = labelMap[id].orEmpty()
                        if (endpoint == PointerOverlayRootView.Endpoint.END) "${base}E" else base
                    },
                    draggingIds = draggingIds,
                    onDragStart = { id, endpoint ->
                        val key = draggingKey(id, endpoint)
                        draggingIds.add(key)
                        dragOwners[key] = state.activeId
                    },
                    onDragMove = { _, _, _, _ ->
                    },
                    onDragEnd = { id, endpoint, centerX, centerY ->
                        val key = draggingKey(id, endpoint)
                        draggingIds.remove(key)
                        val owner = dragOwners.remove(key)
                        val (sx, sy) = v.localCenterToScreen(centerX, centerY)
                        launchCommand {
                            PresetSession.editPoints(app, owner) { current -> current.map { point ->
                                if (point.id != id) point
                                else if (endpoint == PointerOverlayRootView.Endpoint.END) point.copy(dragToX = sx, dragToY = sy)
                                else if (point.actionType == ACTION_TYPE_DRAG) point.copy(x = sx, y = sy)
                                else point.copy(x = sx, y = sy, dragToX = sx, dragToY = sy)
                            } }
                        }
                    }
                )
            }
        }
    }

    private suspend fun loadReservationPrefsInto(v: PointerOverlayRootView) {
        val runSec = ReservationPrefsStore.runSecondsFlow(app).first()
        val restSec = ReservationPrefsStore.restSecondsFlow(app).first()
        val repeatCount = ReservationPrefsStore.repeatCountFlow(app).first()
        v.setReservationValuesFromStore(runSec, restSec, repeatCount)
    }


    private fun startPrefsCollect() {
        prefsJob?.cancel()
        prefsJob = scope.launch {
            launch {
                SequencePrefsStore.sequenceRunningFlow(app).collectLatest { running ->
                    sequenceRunning = running
                    root?.setSequenceRunning(running)
                }
            }
        }
    }

    private fun startCollectIfNeeded() {
        if (collectStarted) return
        if (!added) return
        if (root == null) return
        collectStarted = true
        startCollect()
    }

    private suspend fun ensurePointsLoaded() {
        check(PresetSession.state.value.ready)
        latestPoints = PresetSession.state.value.points
    }

    private fun initializeAfterLayout() {
        launchCommand {
            if (collectStarted || root == null) return@launchCommand
            val (dx, dy) = root!!.getPointerLayerOffsetOnScreen()
            PresetStore.read(app)
            PointsStore.migrateToScreenCoordsIfNeeded(app, dx.toFloat(), dy.toFloat())
            SungyoonHelperService.awaitHoldStopped()
            PresetSession.initialize(app, refreshUnbound = true)
            startCollectIfNeeded()
        }
    }

    private fun queueSettings() {
        val state = PresetSession.state.value
        if (!state.ready) return
        pendingSettings = state.activeId to PresetSettings(tapIntervalMs, dragDurationMs, randomTouchRadiusDp, repeatEnabled, touchAnimEnabled)
        settingsPersistJob?.cancel()
        settingsPersistJob = scope.launch {
            delay(250L)
            launchCommand { flushPendingLocked() }
        }
    }

    private suspend fun flushPendingLocked() {
        settingsPersistJob?.cancel()
        settingsPersistJob = null
        while (true) {
            val pending = pendingSettings ?: return
            PresetSession.editSettings(app, pending.first, pending.second)
            if (pendingSettings == pending) pendingSettings = null
        }
    }

    private fun launchCommand(block: suspend () -> Unit) {
        scope.launch {
            commandMutex.withLock {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toast(app.getString(R.string.preset_storage_error)) }
            }
        }
    }

    private fun performAddPointer() = addPointer(ACTION_TYPE_TAP)
    private fun performAddDragPointer() = addPointer(ACTION_TYPE_DRAG)

    private fun addPointer(actionType: String) {
        val view = root ?: return
        val sourceId = PresetSession.state.value.activeId
        launchCommand {
            flushPendingLocked()
            val drag = actionType == ACTION_TYPE_DRAG
            if (actionType == ACTION_TYPE_HOLD) {
                if (Build.VERSION.SDK_INT < 26) {
                    toast(app.getString(R.string.hold_requires_android_8))
                    return@launchCommand
                }
                val limit = minOf(9, android.accessibilityservice.GestureDescription.getMaxStrokeCount() - 1)
                if (PresetSession.state.value.points.count { it.actionType == ACTION_TYPE_HOLD } >= limit) {
                    toast(app.getString(R.string.hold_pointer_limit, limit))
                    return@launchCommand
                }
            }
            val cx = view.width / 2f
            val cy = view.height / 2f
            val (sx, sy) = view.localCenterToScreen(cx, cy)
            val endX = (cx + 120f * app.resources.displayMetrics.density)
                .coerceIn(pointerHalfSizePx(), (view.width - pointerHalfSizePx()).coerceAtLeast(pointerHalfSizePx()))
            val (ex, ey) = view.localCenterToScreen(endX, cy)
            PresetSession.editPoints(app, sourceId) { points ->
                if (points.size >= 2000) points else points + HighlightingPoint(
                    x = sx, y = sy, index = (points.maxOfOrNull { it.index } ?: -1) + 1,
                    delayMs = tapIntervalMs, actionType = actionType,
                    dragToX = if (drag) ex else sx, dragToY = if (drag) ey else sy,
                    dragDurationMs = dragDurationMs
                )
            }
        }
    }

    private fun secondsToMs(seconds: Float): Long {
        val raw = ((seconds * 1000f) + 0.5f).toLong()
        return clampDragDurationMs(raw)
    }

    private fun clampDragDurationMs(ms: Long): Long {
        return ms.coerceIn(minDragDurationMs, maxDragDurationMs)
    }

    private fun clampRandomRadiusDp(dp: Int): Int {
        return dp.coerceIn(minRandomRadiusDp, maxRandomRadiusDp)
    }

    private fun pointerHalfSizePx(): Float {
        return 56f * app.resources.displayMetrics.density / 2f
    }

    private fun draggingKey(id: String, endpoint: PointerOverlayRootView.Endpoint): String {
        return if (endpoint == PointerOverlayRootView.Endpoint.START) "$id:start" else "$id:end"
    }

    private fun requestStopSequence() {
        try {
            app.sendBroadcast(
                Intent(SungyoonHelperService.ACTION_STOP_SEQUENCE).apply {
                    setPackage(app.packageName)
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun registerSequenceStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(SungyoonHelperService.ACTION_SEQUENCE_STARTED)
            addAction(SungyoonHelperService.ACTION_SEQUENCE_FINISHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    SungyoonHelperService.ACTION_SEQUENCE_STARTED -> {
                        sequenceRunning = true
                        root?.setSequenceRunning(true)
                    }
                    SungyoonHelperService.ACTION_SEQUENCE_FINISHED -> {
                        sequenceRunning = false
                        root?.setSequenceRunning(false)
                    }
                }
            }
        }
        stateReceiver = receiver
        try {
            ContextCompat.registerReceiver(
                app,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Throwable) {
            receiverRegistered = false
            stateReceiver = null
        }
    }

    private fun unregisterSequenceStateReceiver() {
        if (!receiverRegistered) return
        try {
            stateReceiver?.let { app.unregisterReceiver(it) }
        } catch (_: Throwable) {
        } finally {
            receiverRegistered = false
            stateReceiver = null
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun setOverlayFocusableForIme(enable: Boolean) {
        val v = root ?: return
        val lp = overlayLp ?: return
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.flags = if (enable) {
            lp.flags and notFocusable.inv()
        } else {
            lp.flags or notFocusable
        }
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Throwable) {
        }
    }

    private fun toast(msg: String) {
        // ✅ 시스템 Toast가 차단될 수 있으니 오버레이 토스트를 우선 사용
        try {
            com.sungyoon.helper.util.OverlayToast.show(app, msg)
            return
        } catch (_: Throwable) {}

        // ✅ 그래도 실패하면 일반 Toast 시도(차단될 수 있음)
        try {
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }
}
