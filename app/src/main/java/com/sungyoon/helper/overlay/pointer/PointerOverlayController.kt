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
import com.sungyoon.helper.R
import com.sungyoon.helper.SungyoonHelperService
import com.sungyoon.helper.core.permissions.isOverlayGranted
import com.sungyoon.helper.core.permissions.isServiceEnabled
import com.sungyoon.helper.core.permissions.openAccessibilitySettings
import com.sungyoon.helper.core.permissions.openOverlaySettings
import com.sungyoon.helper.data.DragDurationStore
import com.sungyoon.helper.data.PointsStore
import com.sungyoon.helper.data.PointerSizeStore
import com.sungyoon.helper.data.ReservationPrefsStore
import com.sungyoon.helper.data.ReservationRuntimeStore
import com.sungyoon.helper.data.SequencePrefsStore
import com.sungyoon.helper.data.TapIntervalStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_DRAG
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_TAP
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
    private var tapIntervalPersistJob: Job? = null
    private var dragDurationPersistJob: Job? = null

    private var latestPoints: List<HighlightingPoint> = emptyList()
    private val draggingIds = HashSet<String>()

    private var sequenceRunning: Boolean = false
    private var repeatEnabled: Boolean = true
    private var tapIntervalMs: Long = 1000L
    private var dragDurationMs: Long = 300L
    private var touchAnimEnabled: Boolean = true

    private var stateReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    private var overlayLp: WindowManager.LayoutParams? = null
    private val minDragDurationMs = 100L
    private val maxDragDurationMs = 10_000L

    fun isShowing(): Boolean = added

    fun show() {
        try {
            app.sendBroadcast(
                Intent(SungyoonHelperService.ACTION_PAUSE_RESERVATION).apply {
                    setPackage(app.packageName)
                }
            )
        } catch (_: Throwable) {}

        requestStopSequence()
        sequenceRunning = false
        if (added) return

        if (!isOverlayGranted(app)) {
            app.openOverlaySettings()
            toast("오버레이 권한이 필요합니다.")
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

            setOnReservationStartClick { runSec, restSec, repeatCount ->
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
                            toast("예약 작업 재개")
                            hide()
                            return@launch
                        }

                        toast("이미 예약이 진행중입니다. '예약 취소' 후 다시 시작하세요.")
                        return@launch
                    }

                    // ✅ 초 단위 저장
                    ReservationPrefsStore.setRunSeconds(app, runSec)
                    ReservationPrefsStore.setRestSeconds(app, restSec)
                    ReservationPrefsStore.setRepeatCount(app, repeatCount)

                    SequencePrefsStore.setPointerPanelVisible(app, true)
                    SequencePrefsStore.setReservationPanelVisible(app, true)


                    if (!isServiceEnabled(app)) {
                        app.openAccessibilitySettings()
                        toast("접근성 서비스(ON)가 필요합니다.")
                        return@launch
                    }

                    ensurePointsLoaded()
                    if (latestPoints.isEmpty()) {
                        toast("포인트가 없습니다. 포인터를 추가하세요.")
                        return@launch
                    }

                    // ✅ 서비스로 초 단위 전달 (새 Extra 사용)
                    app.sendBroadcast(Intent(SungyoonHelperService.ACTION_START_RESERVATION).apply {
                        setPackage(app.packageName)
                        putExtra(SungyoonHelperService.EXTRA_RUN_SEC, runSec)
                        putExtra(SungyoonHelperService.EXTRA_REST_SEC, restSec)
                        putExtra(SungyoonHelperService.EXTRA_REPEAT_COUNT, repeatCount)
                    })

                    toast("예약 작업 시작")
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

            setOnClearAllClick {
                scope.launch {
                    PointsStore.clear(app)
                    toast("전체 삭제 완료")
                }
            }

            setOnCloseClick { hide() }

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

            setOnPointerSizeChanged { level ->
                scope.launch { PointerSizeStore.setPointerSizeLevel(app, level) }
            }

            setOnRequestIme { enable ->
                setOverlayFocusableForIme(enable)
            }

            setOnDeletePointClick { id ->
                scope.launch {
                    PointsStore.deletePoint(app, id)
                    toast("포인터 삭제")
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
            toast("오버레이 표시 실패: ${t.javaClass.simpleName}")
            return
        }

        registerSequenceStateReceiver()
        startCollect()
        startPrefsCollect()

        root?.post {
            val rv = root ?: return@post
            scope.launch {
                val (dx, dy) = rv.getPointerLayerOffsetOnScreen()
                PointsStore.migrateToScreenCoordsIfNeeded(app, dx.toFloat(), dy.toFloat())
            }
        }

        scope.launch {
            tapIntervalMs = TapIntervalStore.tapIntervalMsFlow(app).first().coerceAtLeast(100L)
            root?.setTapIntervalSeconds(tapIntervalMs / 1000f)
            dragDurationMs = clampDragDurationMs(DragDurationStore.dragDurationMsFlow(app).first())
            root?.setDragDurationSeconds(dragDurationMs / 1000f)

            sequenceRunning = SequencePrefsStore.sequenceRunningFlow(app).first()
            repeatEnabled = SequencePrefsStore.repeatEnabledFlow(app).first()
            touchAnimEnabled = SequencePrefsStore.touchAnimationEnabledFlow(app).first()

            root?.setSequenceRunning(sequenceRunning)
            root?.setRepeatEnabled(repeatEnabled)
            root?.setTouchAnimationEnabled(touchAnimEnabled)

            val sizeLevel = PointerSizeStore.pointerSizeLevelFlow(app).first()
            root?.setPointerSizeLevel(sizeLevel)

            // ✅ 여기서 pref를 읽고
            val panelVisiblePref = SequencePrefsStore.pointerPanelVisibleFlow(app).first()
            val reservationVisiblePref = SequencePrefsStore.reservationPanelVisibleFlow(app).first()

            // ✅ 같은 스코프에서 바로 사용
            val rv = root ?: return@launch
            rv.post {
                val v = root ?: return@post

                val effectivePanelVisible = if (reservationVisiblePref) true else panelVisiblePref
                v.setControlPanelVisibleFromController(effectivePanelVisible)

                if (reservationVisiblePref) {
                    v.openReservationPanel()
                    scope.launch { loadReservationPrefsInto(v) } // ✅ 예약값 복원 주입
                } else {
                    v.closeReservationPanel()
                }
            }
        }
    }

    fun hide() {

        root?.let { v ->
            val panelVisibleNow = v.isControlPanelVisible()
            val reservationVisibleNow = v.isReservationPanelVisible()
            scope.launch {
                SequencePrefsStore.setPointerPanelVisible(app, panelVisibleNow)
                SequencePrefsStore.setReservationPanelVisible(app, reservationVisibleNow)
            }
        }


        collectJob?.cancel()
        collectJob = null
        prefsJob?.cancel()
        prefsJob = null
        tapIntervalPersistJob?.cancel()
        tapIntervalPersistJob = null
        dragDurationPersistJob?.cancel()
        dragDurationPersistJob = null
        unregisterSequenceStateReceiver()
        if (!added) return
        root?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        root = null
        added = false
        overlayLp = null
        draggingIds.clear()
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
            toast("시퀀스 중지")
            return
        }

        if (!isServiceEnabled(app)) {
            app.openAccessibilitySettings()
            toast("접근성 서비스(ON)가 필요합니다.")
            return
        }

        scope.launch {
            ensurePointsLoaded()
            if (latestPoints.isEmpty()) {
                toast("포인트가 없습니다. 포인터를 추가하세요.")
                return@launch
            }

            app.sendBroadcast(
                Intent(SungyoonHelperService.ACTION_START_SEQUENCE).apply {
                    setPackage(app.packageName)
                }
            )
            toast(if (repeatEnabled) "반복 시퀀스 시작" else "시퀀스 시작")
            hide()
        }
    }

    private fun startCollect() {
        val v = root ?: return
        collectJob?.cancel()
        collectJob = scope.launch {
            PointsStore.pointsFlow(app).collectLatest { points ->
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
                        scope.launch {
                            val (sx, sy) = v.localCenterToScreen(centerX, centerY)
                            if (endpoint == PointerOverlayRootView.Endpoint.START) {
                                PointsStore.updatePointPosition(app, id, sx, sy)
                            } else {
                                PointsStore.updateDragEndPosition(app, id, sx, sy)
                            }
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
                PointerSizeStore.pointerSizeLevelFlow(app).collectLatest { level ->
                    root?.setPointerSizeLevel(level)
                }
            }
            launch {
                DragDurationStore.dragDurationMsFlow(app).collectLatest { ms ->
                    val clamped = clampDragDurationMs(ms)
                    dragDurationMs = clamped
                    root?.setDragDurationSeconds(clamped / 1000f)
                }
            }
        }
    }

    private suspend fun ensurePointsLoaded() {
        if (latestPoints.isEmpty()) {
            latestPoints = PointsStore.pointsFlow(app).first()
        }
    }

    private fun performAddPointer() {
        val v = root ?: return

        // 혹시 첫 프레임에 아직 측정 전이면 다음 프레임에 재시도
        if (v.width <= 0 || v.height <= 0) {
            v.post { performAddPointer() }
            return
        }

        scope.launch {
            ensurePointsLoaded()

            val nextIndex = (latestPoints.maxOfOrNull { it.index } ?: -1) + 1
            val localCx = v.width / 2f
            val localCy = v.height / 2f
            val (sx, sy) = v.localCenterToScreen(localCx, localCy)

            PointsStore.addPoint(
                context = app,
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

    private fun performAddDragPointer() {
        val v = root ?: return
        if (v.width <= 0 || v.height <= 0) {
            v.post { performAddDragPointer() }
            return
        }

        scope.launch {
            ensurePointsLoaded()

            val nextIndex = (latestPoints.maxOfOrNull { it.index } ?: -1) + 1
            val localCx = v.width / 2f
            val localCy = v.height / 2f
            val endOffset = 120f * app.resources.displayMetrics.density
            val localEndX = (localCx + endOffset)
                .coerceIn(pointerHalfSizePx(), (v.width - pointerHalfSizePx()).coerceAtLeast(pointerHalfSizePx()))
            val localEndY = localCy

            val (sx, sy) = v.localCenterToScreen(localCx, localCy)
            val (ex, ey) = v.localCenterToScreen(localEndX, localEndY)

            PointsStore.addPoint(
                context = app,
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
