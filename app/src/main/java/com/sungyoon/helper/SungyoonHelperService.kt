package com.sungyoon.helper

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.sungyoon.helper.data.DragDurationStore
import com.sungyoon.helper.data.PointsStore
import com.sungyoon.helper.data.RandomTouchRadiusStore
import com.sungyoon.helper.data.ReservationPrefsStore
import com.sungyoon.helper.data.ReservationRuntimeStore
import com.sungyoon.helper.data.SequencePrefsStore
import com.sungyoon.helper.data.TapIntervalStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_DRAG
import com.sungyoon.helper.overlay.floating.FloatingToggleOverlayController
import com.sungyoon.helper.service.dispatchDrag
import com.sungyoon.helper.service.dispatchTap
import com.sungyoon.helper.service.highlight.SequenceOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SungyoonHelperService : AccessibilityService() {


    private val reservationJobGate = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var cachedPhaseEndAtMs: Long = 0L
    @Volatile private var cachedPhase: Int = ReservationRuntimeStore.PHASE_RUN
    @Volatile private var cachedPausedRemainingMs: Long = 0L
    @Volatile private var cachedReservationActive: Boolean = false
    @Volatile private var cachedReservationPaused: Boolean = false
    @Volatile private var cachedRunSec: Int = 60
    @Volatile private var cachedRestSec: Int = 60
    @Volatile private var cachedCycleCurrent: Int = 1
    @Volatile private var cachedCycleTotal: Int = 1
    @Volatile private var cachedPointsSorted: List<HighlightingPoint> = emptyList()
    @Volatile private var cachedTapIntervalMs: Long = 1000L
    @Volatile private var cachedDragDurationMs: Long = 300L
    @Volatile private var cachedRandomTouchRadiusDp: Int = 0
    @Volatile private var cachedRepeatEnabled: Boolean = true
    private val minDragDurationMs = 100L
    private val maxDragDurationMs = 10_000L
    private val minRandomRadiusDp = 0
    private val maxRandomRadiusDp = 120

    private var floatingToggle: FloatingToggleOverlayController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var overlay: SequenceOverlayController? = null

    // 기존 수동(▶) 시퀀스
    private var runnerJob: Job? = null

    // ✅ 예약 시퀀스
    private var reservationJob: Job? = null

    private var commandReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    private var touchAnimationEnabled: Boolean = true
    private var animPrefJob: Job? = null
    private var runtimeCacheJob: Job? = null
    private var sequenceCacheJob: Job? = null

    @Volatile private var lastPauseAt = 0L
    @Volatile private var lastResumeAt = 0L
    private fun tooSoon(now: Long, last: Long) = (now - last) < 200L


    override fun onServiceConnected() {
        super.onServiceConnected()

        // ✅ 이미 등록돼 있으면 먼저 제거(재연결/중복등록 방지)
        if (receiverRegistered) {
            try {
                commandReceiver?.let { unregisterReceiver(it) }
            } catch (_: Throwable) {
            } finally {
                receiverRegistered = false
                commandReceiver = null
            }
        }

        overlay = SequenceOverlayController(service = this, tag = TAG)

        val filter = IntentFilter().apply {
            addAction(ACTION_START_SEQUENCE)
            addAction(ACTION_STOP_SEQUENCE)
            addAction(ACTION_ENSURE_FLOATING_TOGGLE)
            addAction(ACTION_START_RESERVATION)
            addAction(ACTION_STOP_RESERVATION)
            addAction(ACTION_PAUSE_RESERVATION)
            addAction(ACTION_RESUME_RESERVATION)
            addAction(ACTION_RESET_RESERVATION)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "commandReceiver onReceive:action=${intent?.action}")
                when (intent?.action) {
                    ACTION_START_SEQUENCE -> { stopReservation(fromUser = false); startSequence() }
                    ACTION_STOP_SEQUENCE -> stopSequence()
                    ACTION_ENSURE_FLOATING_TOGGLE -> ensureFloatingToggleShown()
                    ACTION_START_RESERVATION -> startReservationFromPrefsOrExtras(intent)
                    ACTION_STOP_RESERVATION -> stopReservation(fromUser = true)
                    ACTION_PAUSE_RESERVATION -> pauseReservation()
                    ACTION_RESET_RESERVATION -> resetReservation(forceFromUser = true)
                    ACTION_RESUME_RESERVATION -> {
                        val manual = intent.getBooleanExtra(EXTRA_MANUAL_RESUME, false)

                        // ✅ 유저 버튼이 아닌 경로에서 온 RESUME는 무시
                        if (!manual) {
                            Log.d(TAG, "RESUME_RESERVATION ignored (not manual)")
                            return
                        }

                        resumeReservationIfNeeded()
                    }
                }
            }
        }

        commandReceiver = receiver
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            Log.d(TAG, "Receiver registered OK(NOT_EXPORTED)")
        } catch (t: Throwable) {
            receiverRegistered = false
            Log.e(TAG, "Receiver register failed", t)
        }

        animPrefJob?.cancel()
        animPrefJob = serviceScope.launch {
            SequencePrefsStore.touchAnimationEnabledFlow(this@SungyoonHelperService).collectLatest { enabled ->
                touchAnimationEnabled = enabled

                val anyRunning = (runnerJob?.isActive == true) || (reservationJob?.isActive == true)
                if (anyRunning) {
                    if (enabled) overlay?.show() else overlay?.hide()
                } else {
                    overlay?.hide()
                }
            }
        }

        startCacheCollectors()
        ensureFloatingToggleShown()

        // ✅ 앱/서비스 재연결 시 진행상황 복원(일시정지 상태면 재개하지 않음)
        serviceScope.launch {
            val snapshot = ReservationRuntimeStore.snapshotFlow(this@SungyoonHelperService).first()
            applyRuntimeSnapshot(snapshot)
            if (snapshot.active && !snapshot.paused) {
                startReservationJobFromRuntime()
            }
        }
    }

    private fun startCacheCollectors() {
        runtimeCacheJob?.cancel()
        runtimeCacheJob = serviceScope.launch {
            ReservationRuntimeStore.snapshotFlow(this@SungyoonHelperService).collectLatest { snapshot ->
                applyRuntimeSnapshot(snapshot)
            }
        }

        sequenceCacheJob?.cancel()
        sequenceCacheJob = serviceScope.launch {
            launch {
                PointsStore.pointsFlow(this@SungyoonHelperService).collectLatest { points ->
                    cachedPointsSorted = points.sortedBy { it.index }
                }
            }
            launch {
                TapIntervalStore.tapIntervalMsFlow(this@SungyoonHelperService).collectLatest { interval ->
                    cachedTapIntervalMs = interval.coerceAtLeast(100L)
                }
            }
            launch {
                DragDurationStore.dragDurationMsFlow(this@SungyoonHelperService).collectLatest { duration ->
                    cachedDragDurationMs = duration.coerceIn(minDragDurationMs, maxDragDurationMs)
                }
            }
            launch {
                RandomTouchRadiusStore.randomTouchRadiusDpFlow(this@SungyoonHelperService).collectLatest { radiusDp ->
                    cachedRandomTouchRadiusDp = radiusDp.coerceIn(minRandomRadiusDp, maxRandomRadiusDp)
                }
            }
            launch {
                SequencePrefsStore.repeatEnabledFlow(this@SungyoonHelperService).collectLatest { repeatEnabled ->
                    cachedRepeatEnabled = repeatEnabled
                }
            }
        }
    }

    private fun applyRuntimeSnapshot(snapshot: ReservationRuntimeStore.Snapshot) {
        cachedReservationActive = snapshot.active
        cachedReservationPaused = snapshot.paused
        cachedPhase = snapshot.phase
        cachedPhaseEndAtMs = snapshot.phaseEndAtMs
        cachedPausedRemainingMs = snapshot.pausedRemainingMs
        cachedRunSec = snapshot.runSec.coerceIn(1, 3600)
        cachedRestSec = snapshot.restSec.coerceIn(1, 3600)
        cachedCycleCurrent = snapshot.cycleCurrent.coerceAtLeast(1)
        cachedCycleTotal = snapshot.cycleTotal.coerceIn(1, 9999)
    }

    private fun ensureFloatingToggleShown() {
        val ft = floatingToggle
        if (ft == null) {
            floatingToggle = FloatingToggleOverlayController(
                context = this,
                overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                onToggle = {
                    TouchPointerOverlay.toggle(this)
                    floatingToggle?.invalidate()
                },
                isOn = { TouchPointerOverlay.isShowing() }
            ).also { it.show() }
        } else {
            if (!ft.isShowing()) ft.show()
            ft.invalidate()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopSequence()
        // 안전상 예약도 중단
        stopReservation(fromUser = false)
    }

    override fun onDestroy() {
        stopSequence()
        stopReservation(fromUser = false)

        overlay?.dispose()
        overlay = null

        floatingToggle?.hide()
        floatingToggle = null

        animPrefJob?.cancel()
        animPrefJob = null
        runtimeCacheJob?.cancel()
        runtimeCacheJob = null
        sequenceCacheJob?.cancel()
        sequenceCacheJob = null

        if (receiverRegistered) {
            try { commandReceiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
            finally {
                receiverRegistered = false
                commandReceiver = null
            }
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    // ----------------------------
    // 기존 수동 시퀀스
    // ----------------------------

    private fun startSequence() {
        if (runnerJob?.isActive == true) return

        runnerJob = serviceScope.launch {
            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, true)
            sendSequenceStarted()

            if (touchAnimationEnabled) overlay?.show() else overlay?.hide()

            try {
                while (isActive) {
                    val points = cachedPointsSorted

                    if (points.isEmpty()) break

                    val intervalMs = cachedTapIntervalMs.coerceAtLeast(100L)

                    for ((i, p) in points.withIndex()) {
                        if (!isActive) break

                        executePointAction(p, label = "${i + 1}")
                        delay(intervalMs)
                    }

                    if (!isActive) break

                    val repeatEnabled = cachedRepeatEnabled

                    if (!repeatEnabled) break
                }
            } finally {
                overlay?.hide()
                SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, false)
                sendSequenceFinished()
            }
        }
    }

    private fun isDragAction(point: HighlightingPoint): Boolean {
        if (point.actionType != ACTION_TYPE_DRAG) return false
        val dx = point.dragToX - point.x
        val dy = point.dragToY - point.y
        return (abs(dx) > 2f || abs(dy) > 2f)
    }

    private fun dragDurationMs(point: HighlightingPoint): Long {
        val global = cachedDragDurationMs
        if (global > 0L) return global.coerceIn(minDragDurationMs, maxDragDurationMs)
        return point.dragDurationMs.coerceIn(minDragDurationMs, maxDragDurationMs)
    }

    private suspend fun executePointAction(point: HighlightingPoint, label: String) {
        if (isDragAction(point)) {
            if (touchAnimationEnabled) overlay?.moveTo(point.x, point.y, label = label)
            dispatchDrag(
                fromX = point.x,
                fromY = point.y,
                toX = point.dragToX,
                toY = point.dragToY,
                durationMs = dragDurationMs(point)
            )
            if (touchAnimationEnabled) overlay?.moveTo(point.dragToX, point.dragToY, label = label)
        } else {
            val (tapX, tapY) = resolveTapTarget(point)
            if (touchAnimationEnabled) overlay?.moveTo(tapX, tapY, label = label)
            dispatchTap(tapX, tapY)
        }
    }

    private fun resolveTapTarget(point: HighlightingPoint): Pair<Float, Float> {
        val radiusDp = cachedRandomTouchRadiusDp.coerceIn(minRandomRadiusDp, maxRandomRadiusDp)
        if (radiusDp <= 0) return point.x to point.y

        val dm = resources.displayMetrics
        val radiusPx = radiusDp * dm.density
        val theta = Random.nextDouble(0.0, PI * 2.0)
        val r = sqrt(Random.nextDouble(0.0, 1.0)) * radiusPx
        val targetX = (point.x + (r * cos(theta)).toFloat())
            .coerceIn(0f, (dm.widthPixels - 1).coerceAtLeast(0).toFloat())
        val targetY = (point.y + (r * sin(theta)).toFloat())
            .coerceIn(0f, (dm.heightPixels - 1).coerceAtLeast(0).toFloat())
        return targetX to targetY
    }

    private suspend fun waitUntilRunPhaseEnd(endAtMs: Long) {
        while (serviceScope.isActive) {
            val active = cachedReservationActive
            val paused = cachedReservationPaused
            if (!active || paused) return

            val now = System.currentTimeMillis()
            val remain = endAtMs - now
            if (remain <= 0L) return

            delay(minOf(250L, remain))
        }
    }

    private fun resetReservation(forceFromUser: Boolean) {
        // 즉시 중단
        reservationJob?.cancel()
        reservationJob = null
        reservationJobGate.set(false)
        overlay?.hide()

        // 캐시 초기화
        cachedReservationActive = false
        cachedReservationPaused = false
        cachedPhaseEndAtMs = 0L
        cachedPhase = ReservationRuntimeStore.PHASE_RUN
        cachedPausedRemainingMs = 0L
        cachedCycleCurrent = 1
        cachedCycleTotal = 1

        serviceScope.launch {
            // 런타임을 맨 초기 상태로 리셋
            ReservationRuntimeStore.reset(this@SungyoonHelperService, statusText = "예약대기중")

            // UI 즉시 반영
            sendReservationStatusChanged("예약대기중")

            // 시퀀스 러닝 상태도 정리
            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, false)
            sendSequenceFinished()
        }
    }

    private fun stopSequence() {
        runnerJob?.cancel()
        runnerJob = null
        overlay?.hide()

        serviceScope.launch {
            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, false)
            sendSequenceFinished()
        }
    }

    // ----------------------------
    // ✅ 예약 시퀀스
    // ----------------------------

    private fun startReservationFromPrefsOrExtras(intent: Intent?) {
        stopSequence()

        serviceScope.launch {
            val alreadyActive = cachedReservationActive
            if (alreadyActive) {
                val paused = cachedReservationPaused
                if (paused) {
                    ReservationRuntimeStore.resume(this@SungyoonHelperService, System.currentTimeMillis())
                }
                startReservationJobFromRuntime()

                val phase = cachedPhase
                val endAt = cachedPhaseEndAtMs
                val remaining = max(0L, endAt - System.currentTimeMillis())
                val text = formatStatus(phase, remainingMs = remaining, paused = false)

                sendReservationStatusChanged(text)
                SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, true)
                sendSequenceStarted()
                return@launch
            }

            if (reservationJob?.isActive == true) {
                stopReservation(fromUser = false)
            }

            // ✅ 초 우선, 없으면 (구버전) 분을 받아 초로 변환, 그것도 없으면 Prefs(초)
            val runSec =
                intent?.getIntExtra(EXTRA_RUN_SEC, -1)?.takeIf { it > 0 }
                    ?: intent?.getIntExtra(EXTRA_RUN_MIN, -1)?.takeIf { it > 0 }?.let { it * 60 }
                    ?: ReservationPrefsStore.runSecondsFlow(this@SungyoonHelperService).first()

            val restSec =
                intent?.getIntExtra(EXTRA_REST_SEC, -1)?.takeIf { it > 0 }
                    ?: intent?.getIntExtra(EXTRA_REST_MIN, -1)?.takeIf { it > 0 }?.let { it * 60 }
                    ?: ReservationPrefsStore.restSecondsFlow(this@SungyoonHelperService).first()

            val repeatCount =
                intent?.getIntExtra(EXTRA_REPEAT_COUNT, -1)?.takeIf { it > 0 }
                    ?: ReservationPrefsStore.repeatCountFlow(this@SungyoonHelperService).first()

            val rs = runSec.coerceIn(1, 3600)
            val ss = restSec.coerceIn(1, 3600)

            val now = System.currentTimeMillis()
            val initialStatus = formatStatus(
                phase = ReservationRuntimeStore.PHASE_RUN,
                remainingMs = rs * 1000L,
                paused = false
            )

            ReservationRuntimeStore.startNew(
                context = this@SungyoonHelperService,
                nowMs = now,
                runSec = rs,
                restSec = ss,
                repeatCount = repeatCount,
                initialStatus = initialStatus
            )

            cachedReservationActive = true
            cachedReservationPaused = false
            cachedPhase = ReservationRuntimeStore.PHASE_RUN
            cachedPhaseEndAtMs = now + (rs * 1000L)
            cachedPausedRemainingMs = 0L
            cachedRunSec = rs
            cachedRestSec = ss
            cachedCycleCurrent = 1
            cachedCycleTotal = repeatCount.coerceIn(1, 9999)

            ReservationRuntimeStore.setStatusText(this@SungyoonHelperService, initialStatus)
            sendReservationStatusChanged(initialStatus)

            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, true)
            sendSequenceStarted()

            startReservationJobFromRuntime()
        }
    }



    private fun startReservationJobFromRuntime() {
        if (reservationJob?.isActive == true) return
        if (!reservationJobGate.compareAndSet(false, true)) return

        reservationJob = serviceScope.launch {
            try {
                while (isActive) {
                    val active = cachedReservationActive
                    if (!active) break

                    val paused = cachedReservationPaused
                    if (paused) break

                    val phase = cachedPhase
                    val endAt = cachedPhaseEndAtMs

                    // ✅ 초 단위로 읽기
                    val runSec = cachedRunSec.coerceIn(1, 3600)
                    val restSec = cachedRestSec.coerceIn(1, 3600)

                    val cycleCurrent = cachedCycleCurrent.coerceAtLeast(1)
                    val cycleTotal = cachedCycleTotal.coerceIn(1, 9999)

                    val now = System.currentTimeMillis()
                    if (endAt <= now) {
                        handlePhaseFinished(
                            phase = phase,
                            nowMs = now,
                            runSec = runSec,
                            restSec = restSec,
                            cycleCurrent = cycleCurrent,
                            cycleTotal = cycleTotal
                        )
                        continue
                    }

                    when (phase) {
                        ReservationRuntimeStore.PHASE_RUN -> runPhaseUntil(endAt)
                        ReservationRuntimeStore.PHASE_REST -> restPhaseUntil(endAt)
                        else -> restPhaseUntil(endAt)
                    }
                }
            } finally {
                overlay?.hide()
                reservationJobGate.set(false)
            }
        }
    }


    private suspend fun runPhaseUntil(endAtMs: Long) {
        if (touchAnimationEnabled) overlay?.show() else overlay?.hide()

        var lastReportedSec: Long = -1L

        while (serviceScope.isActive) {
            val active = cachedReservationActive
            val paused = cachedReservationPaused
            if (!active || paused) return

            val now = System.currentTimeMillis()
            if (now >= endAtMs) return

            val remainingMs = max(0L, endAtMs - now)
            val remainingSec = ceilSeconds(remainingMs)

            if (remainingSec != lastReportedSec) {
                lastReportedSec = remainingSec
                val text = formatStatus(ReservationRuntimeStore.PHASE_RUN, remainingMs, paused = false)
                sendReservationStatusChanged(text)
            }

            val points = cachedPointsSorted
            if (points.isEmpty()) {
                stopReservation(fromUser = false, finalStatus = "포인트가 없어 예약을 종료합니다.")
                return
            }

            val intervalMs = cachedTapIntervalMs.coerceAtLeast(100L)

            for ((i, p) in points.withIndex()) {
                val n = System.currentTimeMillis()
                if (n >= endAtMs) break

                val a2 = cachedReservationActive
                val p2 = cachedReservationPaused
                if (!a2 || p2) return

                if (isDragAction(p)) {
                    val required = dragDurationMs(p)
                    if (n + required > endAtMs) {
                        waitUntilRunPhaseEnd(endAtMs)
                        return
                    }
                }

                executePointAction(p, label = "${i + 1}")

                // 기존과 동일한 interval 대기 로직
                var waited = 0L
                while (waited < intervalMs) {
                    val step = minOf(250L, intervalMs - waited)
                    delay(step)
                    waited += step

                    val t = System.currentTimeMillis()
                    if (t >= endAtMs) break

                    val a3 = cachedReservationActive
                    val p3 = cachedReservationPaused
                    if (!a3 || p3) return
                }
            }
        }
    }


    private suspend fun restPhaseUntil(endAtMs: Long) {
        overlay?.hide()

        var lastReportedSec: Long = -1L

        while (serviceScope.isActive) {
            val active = cachedReservationActive
            val paused = cachedReservationPaused
            if (!active || paused) return

            val now = System.currentTimeMillis()
            if (now >= endAtMs) return

            val remainingMs = max(0L, endAtMs - now)
            val remainingSec = ceilSeconds(remainingMs)

            if (remainingSec != lastReportedSec) {
                lastReportedSec = remainingSec
                val text = formatStatus(ReservationRuntimeStore.PHASE_REST, remainingMs, paused = false)
                sendReservationStatusChanged(text)
            }

            delay(500L)
        }
    }


    private suspend fun handlePhaseFinished(
        phase: Int,
        nowMs: Long,
        runSec: Int,
        restSec: Int,
        cycleCurrent: Int,
        cycleTotal: Int
    ) {
        when (phase) {
            ReservationRuntimeStore.PHASE_RUN -> {
                ReservationRuntimeStore.setPhase(
                    context = this@SungyoonHelperService,
                    phase = ReservationRuntimeStore.PHASE_REST,
                    nowMs = nowMs,
                    durationMs = restSec * 1000L
                )
                cachedPhase = ReservationRuntimeStore.PHASE_REST
                cachedPhaseEndAtMs = nowMs + restSec * 1000L
                cachedPausedRemainingMs = 0L
                cachedReservationPaused = false

                val text = formatStatus(ReservationRuntimeStore.PHASE_REST, restSec * 1000L, paused = false)
                ReservationRuntimeStore.setStatusText(this@SungyoonHelperService, text)
                sendReservationStatusChanged(text)
            }

            ReservationRuntimeStore.PHASE_REST -> {
                val nextCycle = cycleCurrent + 1
                if (nextCycle > cycleTotal) {
                    stopReservation(fromUser = false, finalStatus = "예약 완료")
                    return
                }

                ReservationRuntimeStore.setCycleCurrent(this@SungyoonHelperService, nextCycle)
                cachedCycleCurrent = nextCycle

                ReservationRuntimeStore.setPhase(
                    context = this@SungyoonHelperService,
                    phase = ReservationRuntimeStore.PHASE_RUN,
                    nowMs = nowMs,
                    durationMs = runSec * 1000L
                )
                cachedPhase = ReservationRuntimeStore.PHASE_RUN
                cachedPhaseEndAtMs = nowMs + runSec * 1000L
                cachedPausedRemainingMs = 0L
                cachedReservationPaused = false

                val text = formatStatus(ReservationRuntimeStore.PHASE_RUN, runSec * 1000L, paused = false)
                ReservationRuntimeStore.setStatusText(this@SungyoonHelperService, text)
                sendReservationStatusChanged(text)
            }
        }
    }

    private fun pauseReservation() {
        serviceScope.launch {
            val now = System.currentTimeMillis()

            // ✅ 중복 수신/연타 디바운스 (200ms 이내 동일 동작 무시)
            if (now - lastPauseAt < 200L) return@launch
            lastPauseAt = now

            val active = cachedReservationActive
            if (!active) return@launch

            val paused = cachedReservationPaused
            if (paused) return@launch

            // ✅ remaining 계산: 캐시 우선(스토어 endAt이 0/지연이어도 스킵 방지)
            val cachedEndAt = cachedPhaseEndAtMs
            val remainingFromCache =
                if (cachedEndAt > 0L) kotlin.math.max(0L, cachedEndAt - now) else -1L

            val endAt = if (remainingFromCache >= 0L) cachedEndAt else 0L

            val remaining =
                if (remainingFromCache >= 0L) remainingFromCache
                else kotlin.math.max(0L, endAt - now)

            // ✅ 캐시에 pausedRemaining 저장 (resume 시 0으로 들어오는 상황 보정용)
            cachedPausedRemainingMs = remaining

            // ✅ 런타임 스토어 갱신
            ReservationRuntimeStore.pause(this@SungyoonHelperService, remaining)
            cachedReservationPaused = true

            // ✅ 현재 수행 중인 예약 Job 중지 (pause 상태에서는 Job이 돌아가면 안 됨)
            reservationJob?.cancel()
            reservationJob = null
            // Gate는 startReservationJobFromRuntime()의 finally에서 풀리지만,
            // cancel 직후 재개가 올 수 있으므로 여기서도 안전하게 풀어둠(중복기동 방지에 도움)
            reservationJobGate.set(false)

            overlay?.hide()

            // ✅ 상태 텍스트 업데이트
            val phase = cachedPhase
            val text = formatStatus(phase, remainingMs = remaining, paused = true)
            ReservationRuntimeStore.setStatusText(this@SungyoonHelperService, text)
            sendReservationStatusChanged(text)
        }
    }



    private fun resumeReservationIfNeeded() {
        serviceScope.launch {
            val now = System.currentTimeMillis()

            // ✅ 중복 수신/연타 디바운스 (200ms 이내 동일 동작 무시)
            if (now - lastResumeAt < 200L) return@launch
            lastResumeAt = now

            val active = cachedReservationActive
            if (!active) return@launch

            val paused = cachedReservationPaused
            if (!paused) return@launch

            // ✅ 스토어 pausedRemaining 이 0으로 잘못 저장된 경우(경합/지연) 캐시로 복구

            // ✅ resume 처리(phase_end_at = now + remaining)
            ReservationRuntimeStore.resume(this@SungyoonHelperService, now)

            // ✅ Job 단일 실행 보장(startReservationJobFromRuntime 내부에서 gate로 한번 더 막음)
            startReservationJobFromRuntime()

            // ✅ 캐시 최신화 + UI 텍스트 갱신
            val phase = cachedPhase
            val endAt = now + cachedPausedRemainingMs
            cachedReservationPaused = false

            cachedPhase = phase
            cachedPhaseEndAtMs = endAt
            cachedPausedRemainingMs = 0L

            val remaining = kotlin.math.max(0L, endAt - System.currentTimeMillis())
            val text = formatStatus(phase, remainingMs = remaining, paused = false)
            ReservationRuntimeStore.setStatusText(this@SungyoonHelperService, text)
            sendReservationStatusChanged(text)
        }
    }


    private fun stopReservation(fromUser: Boolean, finalStatus: String? = null) {
        reservationJob?.cancel()
        reservationJob = null
        overlay?.hide()

        serviceScope.launch {
            val active = cachedReservationActive
            if (active) {
                val msg = finalStatus ?: if (fromUser) "예약 중지" else "예약 종료"
                ReservationRuntimeStore.stop(this@SungyoonHelperService, msg)
                sendReservationStatusChanged(msg)
                cachedReservationActive = false
                cachedReservationPaused = false
            }

            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, false)
            sendSequenceFinished()
        }
    }

    private fun ceilSeconds(ms: Long): Long {
        if (ms <= 0L) return 0L
        return (ms + 999L) / 1000L
    }

    private fun formatStatus(phase: Int, remainingMs: Long, paused: Boolean): String {
        val sec = ceilSeconds(remainingMs).coerceAtLeast(0L)

        val core = when (phase) {
            ReservationRuntimeStore.PHASE_RUN -> "작동 중.. 휴식까지 ${sec}초 남음"
            ReservationRuntimeStore.PHASE_REST -> "휴식 중.. 작동까지 ${sec}초 남음"
            else -> "예약 진행 중.."
        }
        return if (paused) "일시정지됨-$core" else core
    }

    private fun sendSequenceStarted() {
        sendBroadcast(Intent(ACTION_SEQUENCE_STARTED).apply { setPackage(packageName) })
    }

    private fun sendSequenceFinished() {
        sendBroadcast(Intent(ACTION_SEQUENCE_FINISHED).apply { setPackage(packageName) })
    }

    private fun sendReservationStatusChanged(text: String) {
        sendBroadcast(
            Intent(ACTION_RESERVATION_STATUS_CHANGED).apply {
                setPackage(packageName)
                putExtra(EXTRA_STATUS_TEXT, text)
            }
        )
    }

    companion object {
        const val ACTION_START_SEQUENCE = "com.sungyoon.helper.action.START_SEQUENCE"
        const val ACTION_STOP_SEQUENCE = "com.sungyoon.helper.action.STOP_SEQUENCE"

        const val ACTION_SEQUENCE_STARTED = "com.sungyoon.helper.action.SEQUENCE_STARTED"
        const val ACTION_SEQUENCE_FINISHED = "com.sungyoon.helper.action.SEQUENCE_FINISHED"

        const val ACTION_ENSURE_FLOATING_TOGGLE = "com.sungyoon.helper.action.ENSURE_FLOATING_TOGGLE"

        // ✅ 예약 액션
        const val ACTION_START_RESERVATION = "com.sungyoon.helper.action.START_RESERVATION"
        const val ACTION_STOP_RESERVATION = "com.sungyoon.helper.action.STOP_RESERVATION"
        const val ACTION_PAUSE_RESERVATION = "com.sungyoon.helper.action.PAUSE_RESERVATION"
        const val ACTION_RESUME_RESERVATION = "com.sungyoon.helper.action.RESUME_RESERVATION"
        const val ACTION_RESET_RESERVATION = "com.sungyoon.helper.action.RESET_RESERVATION"
        const val EXTRA_MANUAL_RESUME = "extra_manual_resume"

        // ✅ 예약 상태 UI 동기화용
        const val ACTION_RESERVATION_STATUS_CHANGED = "com.sungyoon.helper.action.RESERVATION_STATUS_CHANGED"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        // (선택) 시작 시 extras
        const val EXTRA_RUN_MIN = "extra_run_min"
        const val EXTRA_REST_MIN = "extra_rest_min"
        const val EXTRA_REPEAT_COUNT = "extra_repeat_count"

        const val EXTRA_RUN_SEC = "extra_run_sec"
        const val EXTRA_REST_SEC = "extra_rest_sec"

        private const val TAG = "SungyoonHelperService"
    }
}
