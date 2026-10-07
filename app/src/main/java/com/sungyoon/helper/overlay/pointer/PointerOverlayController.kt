package com.sungyoon.helper.overlay.pointer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
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
import com.sungyoon.helper.data.DragDurationStore
import com.sungyoon.helper.data.PointsStore
import com.sungyoon.helper.data.PresetStore
import com.sungyoon.helper.data.RandomTouchRadiusStore
import com.sungyoon.helper.data.ReservationPrefsStore
import com.sungyoon.helper.data.ReservationRuntimeStore
import com.sungyoon.helper.data.SequencePrefsStore
import com.sungyoon.helper.data.SetStore
import com.sungyoon.helper.data.TapIntervalStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_DRAG
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_TAP
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.overlay.set.SetEditorCoordinator
import com.sungyoon.helper.overlay.set.SetEditorHost
import com.sungyoon.helper.overlay.configureFullScreenOverlayBounds
import com.sungyoon.helper.service.set.SetRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class PointerOverlayController(private val app: Context) {

    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: PointerOverlayRootView? = null
    private var added = false

    private var collectJob: Job? = null
    private var prefsJob: Job? = null
    private var tapIntervalPersistJob: Job? = null
    private var dragDurationPersistJob: Job? = null
    private var randomRadiusPersistJob: Job? = null
    private var collectStartFallbackJob: Job? = null
    private var showRequestJob: Job? = null
    private var closeRequestJob: Job? = null
    private var setEditor: SetEditorCoordinator? = null
    private val pointerRepository = PointerEditRepository(app)
    private val editTarget = MutableStateFlow<PointerEditTarget>(PointerEditTarget.Global)
    private val pendingWrites = mutableSetOf<Job>()
    private var managerPausedSet = false
    private var closing = false
    private var startingSet = false
    private var presetSession = 0L

    private var latestPoints: List<HighlightingPoint> = emptyList()
    private var latestPresets: List<PresetEntry> = emptyList()
    private val draggingIds = HashSet<String>()
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
        if (added || closing) return
        if (SetRuntime.active && !SetRuntime.state.value.paused) {
            if (showRequestJob?.isActive == true) return
            sendSetCommand(SungyoonHelperService.ACTION_PAUSE_SET)
            showRequestJob = scope.launch {
                SetRuntime.state.first { !it.active || it.paused }
                showRequestJob = null
                show(forceOpenControlPanel)
            }
            return
        }
        try {
            app.sendBroadcast(
                Intent(SungyoonHelperService.ACTION_PAUSE_RESERVATION).apply {
                    setPackage(app.packageName)
                }
            )
        } catch (_: Throwable) {}

        if (!SetRuntime.active) requestStopSequence()
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
                if (SetRuntime.active) {
                    toast(app.getString(R.string.set_blocked_message))
                    return@setOnReservationStartClick
                }
                scope.launch {
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
                            return@launch
                        }

                        toast(app.getString(R.string.reservation_already_running))
                        return@launch
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
                        return@launch
                    }

                    ensurePointsLoaded()
                    if (latestPoints.isEmpty()) {
                        toast(app.getString(R.string.toast_points_required))
                        return@launch
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
                presetSession++
                openPresetPanel()
            }

            setOnSetClick { setEditor?.open() }

            setOnClearAllClick {
                val target = editTarget.value
                launchWrite {
                    pointerRepository.clear(target)
                    toast(app.getString(R.string.toast_clear_all_done))
                }
            }

            setOnPresetAddCurrentClick {
                if (editTarget.value != PointerEditTarget.Global) return@setOnPresetAddCurrentClick
                scope.launch {
                    ensurePointsLoaded()
                    val created = PresetStore.addPreset(app, latestPoints)
                    root?.setSelectedPresetId(created.id)
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
                    scope.launch {
                        if (PresetStore.renamePreset(app, preset.id, nextName)) {
                            toast(app.getString(R.string.preset_renamed))
                        }
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
                    scope.launch {
                        if (PresetStore.deletePreset(app, preset.id)) {
                            root?.setSelectedPresetId(null)
                            toast(app.getString(R.string.preset_deleted))
                        }
                    }
                }
            }

            setOnPresetUpdateClick { presetId ->
                if (editTarget.value != PointerEditTarget.Global) return@setOnPresetUpdateClick
                val preset = latestPresets.firstOrNull { it.id == presetId } ?: return@setOnPresetUpdateClick
                showConfirmationDialog(
                    title = app.getString(R.string.preset_update_title),
                    message = app.getString(R.string.preset_update_message),
                    confirmText = app.getString(R.string.preset_update),
                    cancelText = app.getString(R.string.dialog_cancel),
                    destructive = false
                ) {
                    scope.launch {
                        ensurePointsLoaded()
                        if (PresetStore.updatePresetPoints(app, preset.id, latestPoints)) {
                            root?.setSelectedPresetId(preset.id)
                            toast(app.getString(R.string.preset_updated))
                        }
                    }
                }
            }

            setOnPresetLoadClick { presetId ->
                val preset = latestPresets.firstOrNull { it.id == presetId } ?: return@setOnPresetLoadClick
                val target = editTarget.value
                val sourceRoot = root ?: return@setOnPresetLoadClick
                val sourceSession = presetSession
                launchWrite {
                    val existingPoints = ensurePointsLoaded(target)
                    val loadAction: suspend () -> Unit = {
                        loadPresetIntoPoints(preset, target)
                        if (root === sourceRoot && presetSession == sourceSession && editTarget.value == target &&
                            sourceRoot.isPresetPanelVisible()) {
                            sourceRoot.setSelectedPresetId(preset.id)
                            sourceRoot.closePresetPanelAndReturn()
                            toast(app.getString(R.string.preset_loaded))
                        }
                    }

                    if (existingPoints.isNotEmpty()) {
                        if (root !== sourceRoot || presetSession != sourceSession || editTarget.value != target ||
                            !sourceRoot.isPresetPanelVisible()) return@launchWrite
                        sourceRoot.showConfirmationDialog(
                            title = app.getString(R.string.preset_load_title),
                            message = app.getString(R.string.preset_load_message),
                            confirmText = app.getString(R.string.dialog_load),
                            cancelText = app.getString(R.string.dialog_cancel),
                            destructive = false
                        ) {
                            launchWrite { loadAction() }
                        }
                    } else {
                        loadAction()
                    }
                }
            }

            setOnCloseClick { hide(resumeSet = true) }

            setOnPlayToggleClick { onPlayToggleClicked() }

            setOnRepeatToggleClick {
                scope.launch {
                    val next = !repeatEnabled
                    repeatEnabled = next
                    SequencePrefsStore.setRepeatEnabled(app, next)
                    setRepeatEnabled(next)
                }
            }

            setOnTouchAnimationToggleClick {
                scope.launch {
                    val next = !touchAnimEnabled
                    touchAnimEnabled = next
                    SequencePrefsStore.setTouchAnimationEnabled(app, next)
                    setTouchAnimationEnabled(next)
                }
            }

            setOnTapIntervalChanged { seconds ->
                val ms = ((seconds * 1000f) + 0.5f).toLong().coerceAtLeast(100L)
                tapIntervalMs = ms
                tapIntervalPersistJob?.cancel()
                tapIntervalPersistJob = scope.launch {
                    delay(250L)
                    TapIntervalStore.setTapIntervalMs(app, ms)
                }
            }

            setOnDragDurationChanged { seconds ->
                val ms = secondsToMs(seconds)
                dragDurationMs = ms
                dragDurationPersistJob?.cancel()
                dragDurationPersistJob = scope.launch {
                    delay(250L)
                    DragDurationStore.setDragDurationMs(app, ms)
                }
            }

            setOnRandomTouchRadiusChanged { radiusDp ->
                val clamped = clampRandomRadiusDp(radiusDp)
                randomTouchRadiusDp = clamped
                randomRadiusPersistJob?.cancel()
                randomRadiusPersistJob = scope.launch {
                    delay(250L)
                    RandomTouchRadiusStore.setRandomTouchRadiusDp(app, clamped)
                }
            }

            setOnRequestIme { enable ->
                setOverlayFocusableForIme(enable)
            }

            setOnDeletePointClick { id ->
                val target = editTarget.value
                launchWrite {
                    pointerRepository.delete(target, id)
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
        configureFullScreenOverlayBounds(v, lp)

        try {
            wm.addView(v, lp)
            root = v
            added = true
            overlayLp = lp
            managerPausedSet = SetRuntime.active
            editTarget.value = PointerEditTarget.Global
            setEditor = createSetEditor(v)
            v.setOtherExecutionBlocked(SetRuntime.active)
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
        rootView.doOnLayout {
            scope.launch {
                try {
                    val currentRoot = root ?: return@launch
                    val (dx, dy) = currentRoot.getPointerLayerOffsetOnScreen()
                    PointsStore.migrateToScreenCoordsIfNeeded(app, dx.toFloat(), dy.toFloat())
                } finally {
                    startCollectIfNeeded()
                }
            }
        }
        collectStartFallbackJob?.cancel()
        collectStartFallbackJob = scope.launch {
            delay(500L)
            val currentRoot = root
            if (currentRoot != null) {
                runCatching {
                    val (dx, dy) = currentRoot.getPointerLayerOffsetOnScreen()
                    PointsStore.migrateToScreenCoordsIfNeeded(app, dx.toFloat(), dy.toFloat())
                }
            }
            startCollectIfNeeded()
        }

        scope.launch {
            val savedInterval = TapIntervalStore.tapIntervalMsFlow(app).first().coerceAtLeast(100L)
            val savedDragDuration = clampDragDurationMs(DragDurationStore.dragDurationMsFlow(app).first())
            val savedRadius = clampRandomRadiusDp(RandomTouchRadiusStore.randomTouchRadiusDpFlow(app).first())
            val savedSequenceRunning = SequencePrefsStore.sequenceRunningFlow(app).first()
            val savedRepeat = SequencePrefsStore.repeatEnabledFlow(app).first()
            val savedAnimation = SequencePrefsStore.touchAnimationEnabledFlow(app).first()
            val panelVisiblePref = SequencePrefsStore.pointerPanelVisibleFlow(app).first()
            val reservationVisiblePref = SequencePrefsStore.reservationPanelVisibleFlow(app).first()
            val presetVisiblePref = SequencePrefsStore.presetPanelVisibleFlow(app).first()
            val setVisiblePref = SequencePrefsStore.setPanelVisibleFlow(app).first()
            // A delayed load must never initialize a newer window or editing session.
            if (root !== v) return@launch
            tapIntervalMs = savedInterval
            dragDurationMs = savedDragDuration
            randomTouchRadiusDp = savedRadius
            sequenceRunning = savedSequenceRunning
            repeatEnabled = savedRepeat
            touchAnimEnabled = savedAnimation
            v.setTapIntervalSeconds(savedInterval / 1000f)
            v.setDragDurationSeconds(savedDragDuration / 1000f)
            v.setRandomTouchRadiusDp(savedRadius)
            v.setSequenceRunning(savedSequenceRunning)
            v.setRepeatEnabled(savedRepeat)
            v.setTouchAnimationEnabled(savedAnimation)
            v.post {
                if (root !== v) return@post

                val effectivePanelVisible = if (forceOpenControlPanel) {
                    true
                } else if (reservationVisiblePref || presetVisiblePref || setVisiblePref || SetRuntime.active) {
                    true
                } else {
                    panelVisiblePref
                }
                v.setControlPanelVisibleFromController(effectivePanelVisible)

                if (SetRuntime.active || setVisiblePref) {
                    setEditor?.open()
                } else if (presetVisiblePref) {
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

    fun hide(resumeSet: Boolean = false) {
        showRequestJob?.cancel()
        showRequestJob = null
        if (!resumeSet) {
            closeRequestJob?.cancel()
            closeRequestJob = null
            closing = false
            removeWindow()
            return
        }
        if (closing) return
        if (resumeSet && added) {
            closing = true
            val editor = setEditor
            closeRequestJob = scope.launch {
                try {
                    editor?.flushWrites()
                    flushWrites()
                    flushSharedOptions()
                    val shouldResume = managerPausedSet && SetRuntime.active
                    removeWindow()
                    if (shouldResume) sendSetCommand(SungyoonHelperService.ACTION_RESUME_SET)
                } finally {
                    closing = false
                    closeRequestJob = null
                }
            }
        } else {
            removeWindow()
        }
    }

    private fun removeWindow() {

        root?.let { v ->
            val panelVisibleNow = v.isControlPanelVisible()
            val reservationVisibleNow = v.isReservationPanelVisible()
            val presetVisibleNow = v.isPresetPanelVisible()
            val setVisibleNow = setEditor?.isOpen == true
            scope.launch {
                SequencePrefsStore.setPointerPanelVisible(app, panelVisibleNow)
                SequencePrefsStore.setReservationPanelVisible(app, reservationVisibleNow)
                SequencePrefsStore.setPresetPanelVisible(app, presetVisibleNow)
                SequencePrefsStore.setSetPanelVisible(app, setVisibleNow)
            }
        }


        collectJob?.cancel()
        collectJob = null
        collectStarted = false
        collectStartFallbackJob?.cancel()
        collectStartFallbackJob = null
        prefsJob?.cancel()
        prefsJob = null
        tapIntervalPersistJob?.cancel()
        tapIntervalPersistJob = null
        dragDurationPersistJob?.cancel()
        dragDurationPersistJob = null
        randomRadiusPersistJob?.cancel()
        randomRadiusPersistJob = null
        unregisterSequenceStateReceiver()
        setEditor?.dispose()
        setEditor = null
        if (!added) return
        root?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        root = null
        added = false
        overlayLp = null
        draggingIds.clear()
        managerPausedSet = false
        editTarget.value = PointerEditTarget.Global
    }

    private fun onPlayToggleClicked() {
        val v = root ?: return
        if (SetRuntime.active) {
            toast(app.getString(R.string.set_blocked_message))
            return
        }

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

        scope.launch {
            ensurePointsLoaded()
            if (latestPoints.isEmpty()) {
                toast(app.getString(R.string.toast_points_required))
                return@launch
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

    private fun createSetEditor(rootView: PointerOverlayRootView): SetEditorCoordinator =
        SetEditorCoordinator(app, scope, object : SetEditorHost {
            override fun showSetContent(view: android.view.View?, showPointers: Boolean) {
                if (root !== rootView) return
                rootView.showSetContent(view, showPointers)
                scope.launch { SequencePrefsStore.setSetPanelVisible(app, view != null) }
            }

            override fun minimizeSetContent() {
                if (root === rootView) rootView.minimizeSetContent()
            }

            override fun setPointerEditTarget(itemId: String?) {
                if (root !== rootView) return
                val next = itemId?.let { PointerEditTarget.Item(it) } ?: PointerEditTarget.Global
                if (editTarget.value == next) return
                draggingIds.clear()
                latestPoints = emptyList()
                rootView.clearDisplayedPoints()
                editTarget.value = next
            }

            override fun addPointer(isDrag: Boolean) {
                if (isDrag) performAddDragPointer() else performAddPointer()
            }

            override fun clearPointers() {
                val target = editTarget.value
                launchWrite {
                    pointerRepository.clear(target)
                    toast(app.getString(R.string.toast_clear_all_done))
                }
            }

            override fun openItemPresets(onBack: () -> Unit) {
                presetSession++
                rootView.openPresetPanel(importMode = true, onBack = {
                    presetSession++
                    onBack()
                })
            }

            override fun renameItem(item: SetItem, onName: (String) -> Unit) {
                rootView.showInputDialog(
                    title = app.getString(R.string.set_rename_title),
                    initialValue = item.name,
                    hint = app.getString(R.string.set_name_hint),
                    confirmText = app.getString(R.string.dialog_save),
                    cancelText = app.getString(R.string.dialog_cancel),
                    onSubmit = onName
                )
            }

            override fun confirmDelete(item: SetItem, onConfirm: () -> Unit) {
                rootView.showConfirmationDialog(
                    title = app.getString(R.string.set_delete_title),
                    message = app.getString(R.string.set_delete_message, item.name),
                    confirmText = app.getString(R.string.dialog_delete),
                    cancelText = app.getString(R.string.dialog_cancel),
                    destructive = true,
                    onConfirm = onConfirm
                )
            }

            override fun toggleSetRun() { onSetRunToggle() }
            override fun requestIme(show: Boolean) { setOverlayFocusableForIme(show) }
        })

    private fun onSetRunToggle() {
        if (SetRuntime.active) {
            sendSetCommand(SungyoonHelperService.ACTION_CANCEL_SET)
            return
        }
        if (startingSet) return
        if (!isServiceEnabled(app)) {
            app.openAccessibilitySettings()
            toast(app.getString(R.string.toast_accessibility_required))
            return
        }
        startingSet = true
        val sourceRoot = root
        scope.launch {
            try {
                setEditor?.flushWrites()
                flushWrites()
                flushSharedOptions()
                if (root !== sourceRoot || sourceRoot == null) return@launch
                if (SetStore.itemsFlow(app).first().none { it.isExecutable }) {
                    toast(app.getString(R.string.set_empty_message))
                    return@launch
                }
                removeWindow()
                sendSetCommand(SungyoonHelperService.ACTION_START_SET)
            } finally {
                startingSet = false
            }
        }
    }

    private fun sendSetCommand(action: String) {
        app.sendBroadcast(Intent(action).setPackage(app.packageName))
    }

    private fun launchWrite(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        pendingWrites += job
        job.invokeOnCompletion { pendingWrites -= job }
    }

    private suspend fun flushWrites() {
        while (pendingWrites.isNotEmpty()) pendingWrites.toList().joinAll()
    }

    private suspend fun flushSharedOptions() {
        tapIntervalPersistJob?.cancel()
        dragDurationPersistJob?.cancel()
        randomRadiusPersistJob?.cancel()
        TapIntervalStore.setTapIntervalMs(app, tapIntervalMs)
        DragDurationStore.setDragDurationMs(app, dragDurationMs)
        RandomTouchRadiusStore.setRandomTouchRadiusDp(app, randomTouchRadiusDp)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startCollect() {
        val v = root ?: return
        collectJob?.cancel()
        collectJob = scope.launch {
            editTarget.flatMapLatest { target ->
                pointerRepository.pointsFlow(target).map { target to it }
            }.collectLatest { (target, points) ->
                if (editTarget.value != target) return@collectLatest
                latestPoints = points
                val sorted = points.sortedBy { it.index }
                val labelMap = HashMap<String, String>(sorted.size)
                sorted.forEachIndexed { i, p -> labelMap[p.id] = "${i + 1}" }

                v.syncPoints(
                    points = points,
                    labelProvider = { id, endpoint ->
                        val base = labelMap[id].orEmpty()
                        if (endpoint == PointerOverlayRootView.Endpoint.END) "${base}E" else base
                    },
                    draggingIds = draggingIds,
                    onDragStart = { id, endpoint ->
                        draggingIds.add(draggingKey(id, endpoint))
                    },
                    onDragMove = { _, _, _, _ ->
                    },
                    onDragEnd = { id, endpoint, centerX, centerY ->
                        draggingIds.remove(draggingKey(id, endpoint))
                        launchWrite {
                            val (sx, sy) = v.localCenterToScreen(centerX, centerY)
                            pointerRepository.move(target, id, endpoint, sx, sy)
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
                SetRuntime.state.collectLatest { state -> root?.setOtherExecutionBlocked(state.active) }
            }
            launch {
                SequencePrefsStore.sequenceRunningFlow(app).collectLatest { running ->
                    sequenceRunning = running
                    root?.setSequenceRunning(running)
                }
            }
            launch {
                SequencePrefsStore.repeatEnabledFlow(app).collectLatest { enabled ->
                    repeatEnabled = enabled
                    root?.setRepeatEnabled(enabled)
                }
            }
            launch {
                SequencePrefsStore.touchAnimationEnabledFlow(app).collectLatest { enabled ->
                    touchAnimEnabled = enabled
                    root?.setTouchAnimationEnabled(enabled)
                }
            }
            launch {
                DragDurationStore.dragDurationMsFlow(app).collectLatest { ms ->
                    val clamped = clampDragDurationMs(ms)
                    dragDurationMs = clamped
                    root?.setDragDurationSeconds(clamped / 1000f)
                }
            }
            launch {
                RandomTouchRadiusStore.randomTouchRadiusDpFlow(app).collectLatest { radiusDp ->
                    val clamped = clampRandomRadiusDp(radiusDp)
                    randomTouchRadiusDp = clamped
                    root?.setRandomTouchRadiusDp(clamped)
                }
            }
            launch {
                PresetStore.presetsFlow(app).collectLatest { presets ->
                    latestPresets = presets.sortedByDescending { it.createdAtEpochMs }
                    root?.setPresetEntries(latestPresets)
                    val selectedId = root?.getSelectedPresetId()
                    if (selectedId != null && latestPresets.none { it.id == selectedId }) {
                        root?.setSelectedPresetId(null)
                    }
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

    private suspend fun ensurePointsLoaded(target: PointerEditTarget = editTarget.value): List<HighlightingPoint> {
        val points = pointerRepository.get(target)
        if (target == editTarget.value) latestPoints = points
        return points
    }

    private suspend fun loadPresetIntoPoints(preset: PresetEntry, target: PointerEditTarget) {
        val points = preset.points
            .sortedBy { it.index }
            .map { presetPoint ->
                val startX = presetPoint.x
                val startY = presetPoint.y
                if (presetPoint.actionType == ACTION_TYPE_DRAG) {
                    HighlightingPoint(
                        x = startX,
                        y = startY,
                        index = presetPoint.index,
                        delayMs = tapIntervalMs,
                        actionType = ACTION_TYPE_DRAG,
                        dragToX = presetPoint.dragToX,
                        dragToY = presetPoint.dragToY,
                        dragDurationMs = clampDragDurationMs(dragDurationMs)
                    )
                } else {
                    HighlightingPoint(
                        x = startX,
                        y = startY,
                        index = presetPoint.index,
                        delayMs = tapIntervalMs,
                        actionType = ACTION_TYPE_TAP,
                        dragToX = startX,
                        dragToY = startY
                    )
                }
            }
        pointerRepository.replace(target, points)
    }

    private fun performAddPointer(target: PointerEditTarget = editTarget.value) {
        val v = root ?: return

        // 혹시 첫 프레임에 아직 측정 전이면 다음 프레임에 재시도
        if (v.width <= 0 || v.height <= 0) {
            v.post { if (root === v) performAddPointer(target) }
            return
        }

        launchWrite {
            val points = ensurePointsLoaded(target)

            val nextIndex = (points.maxOfOrNull { it.index } ?: -1) + 1
            val localCx = v.width / 2f
            val localCy = v.height / 2f
            val (sx, sy) = v.localCenterToScreen(localCx, localCy)

            pointerRepository.add(
                target = target,
                point = HighlightingPoint(
                    x = sx,
                    y = sy,
                    index = nextIndex,
                    delayMs = tapIntervalMs,
                    actionType = ACTION_TYPE_TAP,
                    dragToX = sx,
                    dragToY = sy
                )
            )
        }
    }

    private fun performAddDragPointer(target: PointerEditTarget = editTarget.value) {
        val v = root ?: return
        if (v.width <= 0 || v.height <= 0) {
            v.post { if (root === v) performAddDragPointer(target) }
            return
        }

        launchWrite {
            val points = ensurePointsLoaded(target)

            val nextIndex = (points.maxOfOrNull { it.index } ?: -1) + 1
            val localCx = v.width / 2f
            val localCy = v.height / 2f
            val endOffset = 120f * app.resources.displayMetrics.density
            val localEndX = (localCx + endOffset)
                .coerceIn(0f, (v.width - 1).coerceAtLeast(0).toFloat())
            val localEndY = localCy

            val (sx, sy) = v.localCenterToScreen(localCx, localCy)
            val (ex, ey) = v.localCenterToScreen(localEndX, localEndY)

            pointerRepository.add(
                target = target,
                point = HighlightingPoint(
                    x = sx,
                    y = sy,
                    index = nextIndex,
                    delayMs = tapIntervalMs,
                    actionType = ACTION_TYPE_DRAG,
                    dragToX = ex,
                    dragToY = ey,
                    dragDurationMs = clampDragDurationMs(dragDurationMs)
                )
            )
            toast(app.getString(R.string.pointer_drag_added))
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
