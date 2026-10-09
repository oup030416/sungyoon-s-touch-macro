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
import com.sungyoon.helper.data.PointerSizeStore
import com.sungyoon.helper.data.PointsStore
import com.sungyoon.helper.data.RandomTouchRadiusStore
import com.sungyoon.helper.data.ReservationPrefsStore
import com.sungyoon.helper.data.ReservationRuntimeStore
import com.sungyoon.helper.data.SequencePrefsStore
import com.sungyoon.helper.data.TapIntervalStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.overlay.floating.FloatingToggleOverlayController
import com.sungyoon.helper.service.dispatchDrag
import com.sungyoon.helper.service.dispatchTap
import com.sungyoon.helper.service.currentGestureDisplayBounds
import com.sungyoon.helper.service.randomizedTapTarget
import com.sungyoon.helper.service.highlight.SequenceOverlayController
import com.sungyoon.helper.service.set.SetGestureOptions
import com.sungyoon.helper.service.set.SetServiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.sungyoon.helper.util.PointerSizeSpec
import kotlin.math.max

class SungyoonHelperService : AccessibilityService() {


    private val reservationJobGate = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var cachedPhaseEndAtMs: Long = 0L
    @Volatile private var cachedPhase: Int = ReservationRuntimeStore.PHASE_RUN
    @Volatile private var cachedPausedRemainingMs: Long = 0L
    @Volatile private var cachedNextPointOffset: Int = 0
    @Volatile private var cachedReservationActive: Boolean = false
    @Volatile private var cachedReservationPaused: Boolean = false
    @Volatile private var cachedRunSec: Int = 60
    @Volatile private var cachedRestSec: Int = 60
    @Volatile private var cachedCycleCurrent: Int = 1
    @Volatile private var cachedCycleTotal: Int = 1
    @Volatile private var cachedPointsSorted: List<HighlightingPoint> = emptyList()
    @Volatile private var cachedTapIntervalMs: Long = 1000L
    @Volatile private var cachedDragDurationMs: Long = 300L
    @Volatile private var cachedRandomTouchRadiusDp: Int = 5
    @Volatile private var cachedPointerSizeLevel: Int = PointerSizeSpec.DEFAULT_LEVEL
    @Volatile private var cachedRepeatEnabled: Boolean = true
    private val minDragDurationMs = 100L
    private val maxDragDurationMs = 10_000L
    private val minRandomRadiusDp = 0
    private val maxRandomRadiusDp = 20
    private val touchAnimRadiusExtraDp = 5

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

    private val gestureMutex = Mutex()
    private val configurationRefreshMutex = Mutex()
    private val runtimeRefreshMutex = Mutex()
    private var reservationStartJob: Job? = null
    private val ordinaryCommandJobs = linkedSetOf<Job>()
    private val ordinaryExecutionJobs = linkedSetOf<Job>()
    private var configurationReady = CompletableDeferred<Unit>()
    private var setController: SetServiceController? = null
    private var destroyed = false
    private var serviceGeneration = 0L

    @Volatile private var lastPauseAt = 0L
    @Volatile private var lastResumeAt = 0L
    private fun tooSoon(now: Long, last: Long) = (now - last) < 200L


    override fun onServiceConnected() {
        super.onServiceConnected()
        com.sungyoon.helper.feedback.FeedbackRuntime.recover(this)

        destroyed = false
        serviceGeneration++
        setController?.dispose()
        setController = null
        overlay?.dispose()

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
            addAction(ACTION_START_SET)
            addAction(ACTION_CANCEL_SET)
            addAction(ACTION_PAUSE_SET)
            addAction(ACTION_RESUME_SET)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "commandReceiver onReceive:action=${intent?.action}")
                when (intent?.action) {
                    ACTION_START_SEQUENCE -> {
                        if (!rejectOrdinaryStart()) {
                            stopReservation(fromUser = false)
                            startSequence()
                        }
                    }
                    ACTION_STOP_SEQUENCE -> stopSequence()
                    ACTION_ENSURE_FLOATING_TOGGLE -> ensureFloatingToggleShown()
                    ACTION_START_RESERVATION -> startReservationFromPrefsOrExtras(intent)
                    ACTION_STOP_RESERVATION -> stopReservation(fromUser = true)
                    ACTION_PAUSE_RESERVATION -> pauseReservation()
                    ACTION_RESET_RESERVATION -> resetReservation(forceFromUser = true)
                    ACTION_START_SET -> setController?.start(intent.getStringExtra(EXTRA_SET_ID))
                    ACTION_CANCEL_SET -> setController?.cancel(setId = intent.getStringExtra(EXTRA_SET_ID))
                    ACTION_PAUSE_SET -> setController?.pause(intent.getStringExtra(EXTRA_SET_ID))
                    ACTION_RESUME_SET -> setController?.resume(intent.getStringExtra(EXTRA_SET_ID))
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

                syncTouchAnimationVisibility()
            }
        }

        startCacheCollectors()
        initializeSetController()
        ensureFloatingToggleShown()

        // ✅ 앱/서비스 재연결 시 진행상황 복원(일시정지 상태면 재개하지 않음)
        launchOrdinaryCommand {
            val snapshot = ReservationRuntimeStore.snapshotFlow(this@SungyoonHelperService).first()
            if (setBlocksOrdinaryWork()) return@launchOrdinaryCommand
            applyRuntimeSnapshot(snapshot)
            if (snapshot.active && !snapshot.paused) {
                startReservationJobFromRuntime()
            }
        }
    }

    private fun startCacheCollectors() {
        startRuntimeCacheCollector()
        startSequenceCacheCollectors()
    }

    private fun startRuntimeCacheCollector() {
        runtimeCacheJob?.cancel()
        runtimeCacheJob = serviceScope.launch {
            ReservationRuntimeStore.snapshotFlow(this@SungyoonHelperService).collectLatest { snapshot ->
                applyRuntimeSnapshot(snapshot)
            }
        }
    }

    private fun startSequenceCacheCollectors() {
        sequenceCacheJob?.cancel()
        configurationReady = CompletableDeferred()
        val ready = configurationReady
        sequenceCacheJob = serviceScope.launch {
            val loaded = List(6) { CompletableDeferred<Unit>() }
            launch {
                PointsStore.pointsFlow(this@SungyoonHelperService).collectLatest { points ->
                    cachedPointsSorted = points.sortedBy { it.index }
                    loaded[0].complete(Unit)
                }
            }
            launch {
                TapIntervalStore.tapIntervalMsFlow(this@SungyoonHelperService).collectLatest { interval ->
                    cachedTapIntervalMs = interval.coerceAtLeast(100L)
                    loaded[1].complete(Unit)
                }
            }
            launch {
                DragDurationStore.dragDurationMsFlow(this@SungyoonHelperService).collectLatest { duration ->
                    cachedDragDurationMs = duration.coerceIn(minDragDurationMs, maxDragDurationMs)
                    loaded[2].complete(Unit)
                }
            }
            launch {
                RandomTouchRadiusStore.randomTouchRadiusDpFlow(this@SungyoonHelperService).collectLatest { radiusDp ->
                    cachedRandomTouchRadiusDp = radiusDp.coerceIn(minRandomRadiusDp, maxRandomRadiusDp)
                    loaded[3].complete(Unit)
                }
            }
            launch {
                PointerSizeStore.pointerSizeLevelFlow(this@SungyoonHelperService).collectLatest { level ->
                    cachedPointerSizeLevel = level.coerceIn(PointerSizeSpec.MIN_LEVEL, PointerSizeSpec.MAX_LEVEL)
                    loaded[4].complete(Unit)
                    if (touchAnimationEnabled && anyGestureWorkRunning()) {
                        syncOverlayPointerRadius()
                    }
                }
            }
            launch {
                SequencePrefsStore.repeatEnabledFlow(this@SungyoonHelperService).collectLatest { repeatEnabled ->
                    cachedRepeatEnabled = repeatEnabled
                    loaded[5].complete(Unit)
                }
            }
            loaded.forEach { it.await() }
            ready.complete(Unit)
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null && !ready.isCompleted) ready.completeExceptionally(cause)
            }
        }
    }

    /** Restart the six collectors so queued old emissions cannot overwrite startup snapshots. */
    private suspend fun refreshGestureConfiguration(): Boolean = configurationRefreshMutex.withLock {
        val generation = serviceGeneration
        var restartRequired = false
        try {
            configurationReady.await()
            currentCoroutineContext().ensureActive()
            if (destroyed || generation != serviceGeneration) return@withLock false
            restartRequired = true
            sequenceCacheJob?.cancelAndJoin()
            currentCoroutineContext().ensureActive()
            if (destroyed || generation != serviceGeneration) return@withLock false
            startSequenceCacheCollectors()
            restartRequired = false
            configurationReady.await()
            currentCoroutineContext().ensureActive()
            !destroyed && generation == serviceGeneration
        } finally {
            // A canceled start must not leave the live service without its normal collectors.
            if (restartRequired && !destroyed && generation == serviceGeneration) {
                startSequenceCacheCollectors()
            }
        }
    }

    private suspend fun refreshReservationRuntime(): ReservationRuntimeStore.Snapshot? = runtimeRefreshMutex.withLock {
        val generation = serviceGeneration
        try {
            runtimeCacheJob?.cancelAndJoin()
            currentCoroutineContext().ensureActive()
            if (destroyed || generation != serviceGeneration) return@withLock null
            val snapshot = ReservationRuntimeStore.snapshotFlow(this).first()
            currentCoroutineContext().ensureActive()
            if (destroyed || generation != serviceGeneration) return@withLock null
            applyRuntimeSnapshot(snapshot)
            snapshot
        } finally {
            if (!destroyed && generation == serviceGeneration) startRuntimeCacheCollector()
        }
    }

    private fun applyRuntimeSnapshot(snapshot: ReservationRuntimeStore.Snapshot) {
        cachedReservationActive = snapshot.active
        cachedReservationPaused = snapshot.paused
        cachedPhase = snapshot.phase
        cachedPhaseEndAtMs = snapshot.phaseEndAtMs
        cachedPausedRemainingMs = snapshot.pausedRemainingMs
        cachedNextPointOffset = snapshot.nextPointOffset.coerceAtLeast(0)
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
        setController?.cancel(interrupted = true)
        TouchPointerOverlay.hide()
        stopSequence(force = true)
        // 안전상 예약도 중단
        stopReservation(fromUser = false, force = true)
    }

    override fun onDestroy() {
        destroyed = true
        serviceGeneration++
        setController?.dispose()
        setController = null
        TouchPointerOverlay.hide()
        stopSequence(force = true)
        stopReservation(fromUser = false, force = true)

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

    private fun initializeSetController() {
        setController = SetServiceController(
            context = this,
            parentScope = serviceScope,
            options = { SetGestureOptions(cachedTapIntervalMs, cachedDragDurationMs) },
            awaitConfiguration = { check(refreshGestureConfiguration()) },
            settleOrdinaryWork = { settleOrdinaryWork() },
            execute = { point, label -> executePointAction(point, label, usePointDragDuration = true) },
            onRuntimeChanged = ::syncTouchAnimationVisibility,
            managerVisible = TouchPointerOverlay::isShowing,
        )
    }

    private suspend fun settleOrdinaryWork() {
        val pending = synchronized(ordinaryCommandJobs) { ordinaryCommandJobs.toList() }
        pending.forEach { it.cancel() }
        pending.forEach { it.join() }
        val executions = synchronized(ordinaryExecutionJobs) { ordinaryExecutionJobs.toList() }
        executions.forEach { it.cancel() }
        executions.forEach { it.join() }
        runnerJob?.cancelAndJoin()
        runnerJob = null
        reservationJob?.cancelAndJoin()
        reservationJob = null
        reservationJobGate.set(false)
        cachedReservationActive = false
        cachedReservationPaused = false
        cachedNextPointOffset = 0
        cachedPhaseEndAtMs = 0L
        cachedPausedRemainingMs = 0L
        ReservationRuntimeStore.stop(this, getString(R.string.reservation_waiting))
        SequencePrefsStore.setSequenceRunning(this, false)
        sendReservationStatusChanged(getString(R.string.reservation_waiting))
        sendSequenceFinished()
        overlay?.hide()
    }

    private fun setBlocksOrdinaryWork(): Boolean = setController?.blocksOrdinaryWork == true

    private fun rejectOrdinaryStart(): Boolean = setController?.rejectOrdinaryStart() == true

    private fun launchOrdinaryCommand(block: suspend CoroutineScope.() -> Unit): Job {
        val generation = serviceGeneration
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            if (destroyed || generation != serviceGeneration) return@launch
            block()
        }
        synchronized(ordinaryCommandJobs) { ordinaryCommandJobs.add(job) }
        job.invokeOnCompletion { synchronized(ordinaryCommandJobs) { ordinaryCommandJobs.remove(job) } }
        job.start()
        return job
    }

    private fun trackOrdinaryExecution(job: Job) {
        synchronized(ordinaryExecutionJobs) { ordinaryExecutionJobs.add(job) }
        job.invokeOnCompletion { synchronized(ordinaryExecutionJobs) { ordinaryExecutionJobs.remove(job) } }
    }

    private fun launchReservationStart(block: suspend CoroutineScope.() -> Unit) {
        if (reservationStartJob?.isActive == true) return
        val job = launchOrdinaryCommand(block)
        reservationStartJob = job
        job.invokeOnCompletion {
            if (reservationStartJob === job) reservationStartJob = null
        }
    }

    private fun cancelReservationStart() {
        reservationStartJob?.cancel()
        reservationStartJob = null
    }

    private fun anyGestureWorkRunning(): Boolean =
        runnerJob?.isActive == true || reservationJob?.isActive == true || setController?.isGesturing == true

    private fun syncTouchAnimationVisibility() {
        if (destroyed) return
        if (touchAnimationEnabled && anyGestureWorkRunning()) {
            overlay?.show()
            syncOverlayPointerRadius()
        } else {
            overlay?.hide()
        }
    }

    // ----------------------------
    // 기존 수동 시퀀스
    // ----------------------------

    private fun startSequence() {
        if (rejectOrdinaryStart()) return
        if (runnerJob?.isActive == true) return

        runnerJob = serviceScope.launch {
            if (!refreshGestureConfiguration() || rejectOrdinaryStart()) return@launch
            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, true)
            sendSequenceStarted()

            if (touchAnimationEnabled) {
                overlay?.show()
                syncOverlayPointerRadius()
            } else {
                overlay?.hide()
            }

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
        }.also(::trackOrdinaryExecution)
    }

    private fun dragDurationMs(point: HighlightingPoint): Long {
        val global = cachedDragDurationMs
        if (global > 0L) return global.coerceIn(minDragDurationMs, maxDragDurationMs)
        return point.dragDurationMs.coerceIn(minDragDurationMs, maxDragDurationMs)
    }

    private fun syncOverlayPointerRadius() {
        val radiusDp = PointerSizeSpec.radiusDpForLevel(cachedPointerSizeLevel) + touchAnimRadiusExtraDp
        overlay?.setPointerRadiusDp(radiusDp)
    }

    private suspend fun executePointAction(
        point: HighlightingPoint,
        label: String,
        usePointDragDuration: Boolean = false
    ): Boolean = gestureMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (destroyed) return@withLock false
        val generation = serviceGeneration
        val animation = overlay
        val animate = touchAnimationEnabled
        if (animate) {
            animation?.show()
            syncOverlayPointerRadius()
        }
        if (point.isEffectiveDrag) {
            val durationMs = if (usePointDragDuration) {
                point.dragDurationMs.coerceIn(minDragDurationMs, maxDragDurationMs)
            } else {
                dragDurationMs(point)
            }
            coroutineScope {
                val visualJob = if (animate) launch {
                    animation?.moveTo(point.x, point.y, label)
                    animation?.triggerPop()
                    animation?.animateDragRealtime(point.x, point.y, point.dragToX, point.dragToY, durationMs, label)
                } else null
                try {
                    val success = dispatchSettled(durationMs) {
                        dispatchDrag(point.x, point.y, point.dragToX, point.dragToY, durationMs)
                    }
                    if (animate && !destroyed && generation == serviceGeneration) {
                        animation?.moveTo(point.dragToX, point.dragToY, label)
                    }
                    success
                } finally {
                    visualJob?.cancelAndJoin()
                }
            }
        } else {
            val (tapX, tapY) = resolveTapTarget(point)
            if (animate) {
                animation?.moveTo(tapX, tapY, label)
                animation?.triggerPop()
            }
            dispatchSettled(50L) { dispatchTap(tapX, tapY) }
        }
    }

    private suspend fun dispatchSettled(durationMs: Long, dispatch: suspend () -> Boolean): Boolean =
        withContext(NonCancellable) {
            try {
                // Keep the gesture channel occupied until Android completes/cancels the dispatched stroke.
                withTimeout(durationMs + 2_000L) { dispatch() }
            } catch (failure: Exception) {
                Log.e(TAG, "Gesture dispatch failed", failure)
                false
            }
        }

    private fun resolveTapTarget(point: HighlightingPoint): Pair<Float, Float> {
        val radiusDp = cachedRandomTouchRadiusDp.coerceIn(minRandomRadiusDp, maxRandomRadiusDp)
        if (radiusDp <= 0) return point.x to point.y

        return randomizedTapTarget(
            x = point.x,
            y = point.y,
            radiusPx = radiusDp * resources.displayMetrics.density,
            bounds = currentGestureDisplayBounds(this),
        )
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
        if (setBlocksOrdinaryWork()) return
        cancelReservationStart()
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
        cachedNextPointOffset = 0
        cachedCycleCurrent = 1
        cachedCycleTotal = 1

        launchOrdinaryCommand {
            // 런타임을 맨 초기 상태로 리셋
            ReservationRuntimeStore.reset(this@SungyoonHelperService, statusText = "예약대기중")

            // UI 즉시 반영
            sendReservationStatusChanged("예약대기중")

            // 시퀀스 러닝 상태도 정리
            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, false)
            sendSequenceFinished()
        }
    }

    private fun stopSequence(force: Boolean = false) {
        if (!force && setController?.isTransitioning == true) {
            runnerJob?.cancel()
            runnerJob = null
            return
        }
        if (!force && setBlocksOrdinaryWork() && runnerJob == null) return
        runnerJob?.cancel()
        runnerJob = null
        overlay?.hide()

        launchOrdinaryCommand {
            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, false)
            sendSequenceFinished()
        }
    }

    // ----------------------------
    // ✅ 예약 시퀀스
    // ----------------------------

    private fun startReservationFromPrefsOrExtras(intent: Intent?) {
        if (rejectOrdinaryStart()) return
        stopSequence()

        launchReservationStart {
            if (!refreshGestureConfiguration() || rejectOrdinaryStart()) return@launchReservationStart
            val alreadyActive = cachedReservationActive
            if (alreadyActive) {
                val paused = cachedReservationPaused
                if (paused) {
                    ReservationRuntimeStore.resume(this@SungyoonHelperService, System.currentTimeMillis())
                }
                val snapshot = refreshReservationRuntime() ?: return@launchReservationStart
                if (!snapshot.active || snapshot.paused || rejectOrdinaryStart()) return@launchReservationStart
                startReservationJobFromRuntime(configurationFresh = true)

                val phase = snapshot.phase
                val endAt = snapshot.phaseEndAtMs
                val remaining = max(0L, endAt - System.currentTimeMillis())
                val text = formatStatus(phase, remainingMs = remaining, paused = false)

                sendReservationStatusChanged(text)
                SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, true)
                sendSequenceStarted()
                return@launchReservationStart
            }

            if (reservationJob?.isActive == true) {
                stopReservation(fromUser = false, cancelPendingStart = false)
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

            if (rejectOrdinaryStart()) return@launchReservationStart

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
            cachedNextPointOffset = 0
            cachedRunSec = rs
            cachedRestSec = ss
            cachedCycleCurrent = 1
            cachedCycleTotal = repeatCount.coerceIn(1, 9999)

            ReservationRuntimeStore.setStatusText(this@SungyoonHelperService, initialStatus)
            sendReservationStatusChanged(initialStatus)

            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, true)
            sendSequenceStarted()

            startReservationJobFromRuntime(configurationFresh = true)
        }
    }



    private fun startReservationJobFromRuntime(configurationFresh: Boolean = false) {
        if (setBlocksOrdinaryWork()) return
        if (reservationJob?.isActive == true) return
        if (!reservationJobGate.compareAndSet(false, true)) return

        reservationJob = serviceScope.launch {
            try {
                if (!configurationFresh && !refreshGestureConfiguration()) return@launch
                if (setBlocksOrdinaryWork()) return@launch
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
        }.also(::trackOrdinaryExecution)
    }


    private suspend fun runPhaseUntil(endAtMs: Long) {
        if (touchAnimationEnabled) {
            overlay?.show()
            syncOverlayPointerRadius()
        } else {
            overlay?.hide()
        }

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

            val startOffset = normalizePointOffset(cachedNextPointOffset, points.size)
            if (startOffset != cachedNextPointOffset) {
                persistNextPointOffset(startOffset)
            }
            val intervalMs = cachedTapIntervalMs.coerceAtLeast(100L)

            for (stepIndex in points.indices) {
                val n = System.currentTimeMillis()
                if (n >= endAtMs) break

                val a2 = cachedReservationActive
                val p2 = cachedReservationPaused
                if (!a2 || p2) return

                val pointOffset = (startOffset + stepIndex) % points.size
                val p = points[pointOffset]
                if (p.isEffectiveDrag) {
                    val required = dragDurationMs(p)
                    if (n + required > endAtMs) {
                        waitUntilRunPhaseEnd(endAtMs)
                        return
                    }
                }

                executePointAction(p, label = "${pointOffset + 1}")
                persistNextPointOffset((pointOffset + 1) % points.size)

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
        if (setBlocksOrdinaryWork()) return
        val pendingStarter = reservationStartJob
        cancelReservationStart()
        launchOrdinaryCommand {
            pendingStarter?.join()
            val snapshot = refreshReservationRuntime() ?: return@launchOrdinaryCommand
            if (setBlocksOrdinaryWork()) return@launchOrdinaryCommand
            val now = System.currentTimeMillis()

            // ✅ 중복 수신/연타 디바운스 (200ms 이내 동일 동작 무시)
            // Finish pausing a canceled resume even when the previous pause was recent.
            if (pendingStarter == null && now - lastPauseAt < 200L) return@launchOrdinaryCommand
            lastPauseAt = now

            val active = snapshot.active
            if (!active) return@launchOrdinaryCommand

            val paused = snapshot.paused
            if (paused) return@launchOrdinaryCommand

            // ✅ remaining 계산: 캐시 우선(스토어 endAt이 0/지연이어도 스킵 방지)
            val cachedEndAt = snapshot.phaseEndAtMs
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
        if (rejectOrdinaryStart()) return
        launchReservationStart {
            if (!refreshGestureConfiguration() || rejectOrdinaryStart()) return@launchReservationStart
            val now = System.currentTimeMillis()

            // ✅ 중복 수신/연타 디바운스 (200ms 이내 동일 동작 무시)
            if (now - lastResumeAt < 200L) return@launchReservationStart
            lastResumeAt = now

            val active = cachedReservationActive
            if (!active) return@launchReservationStart

            val paused = cachedReservationPaused
            if (!paused) return@launchReservationStart

            // ✅ resume 처리(phase_end_at = now + remaining)
            ReservationRuntimeStore.resume(this@SungyoonHelperService, now)
            val snapshot = refreshReservationRuntime() ?: return@launchReservationStart
            if (!snapshot.active || snapshot.paused || rejectOrdinaryStart()) return@launchReservationStart
            startReservationJobFromRuntime(configurationFresh = true)

            val phase = snapshot.phase
            val endAt = snapshot.phaseEndAtMs
            val remaining = kotlin.math.max(0L, endAt - System.currentTimeMillis())
            val text = formatStatus(phase, remainingMs = remaining, paused = false)
            ReservationRuntimeStore.setStatusText(this@SungyoonHelperService, text)
            sendReservationStatusChanged(text)
        }
    }


    private fun stopReservation(
        fromUser: Boolean,
        finalStatus: String? = null,
        force: Boolean = false,
        cancelPendingStart: Boolean = true,
    ) {
        val pendingStarter = if (cancelPendingStart) reservationStartJob else null
        if (cancelPendingStart) cancelReservationStart()
        if (!force && setController?.isTransitioning == true) {
            reservationJob?.cancel()
            reservationJob = null
            return
        }
        if (!force && setBlocksOrdinaryWork() && reservationJob == null && !cachedReservationActive) return
        reservationJob?.cancel()
        reservationJob = null
        overlay?.hide()
        cachedNextPointOffset = 0

        launchOrdinaryCommand {
            pendingStarter?.join()
            val msg = finalStatus ?: if (fromUser) "예약 중지" else "예약 종료"
            if (ReservationRuntimeStore.stopIfActive(this@SungyoonHelperService, msg)) {
                sendReservationStatusChanged(msg)
            }
            cachedReservationActive = false
            cachedReservationPaused = false
            cachedPausedRemainingMs = 0L

            SequencePrefsStore.setSequenceRunning(this@SungyoonHelperService, false)
            sendSequenceFinished()
        }
    }

    private suspend fun persistNextPointOffset(offset: Int) {
        val normalized = offset.coerceAtLeast(0)
        if (cachedNextPointOffset == normalized) return
        cachedNextPointOffset = normalized
        ReservationRuntimeStore.setNextPointOffset(this@SungyoonHelperService, normalized)
    }

    private fun normalizePointOffset(offset: Int, pointCount: Int): Int {
        if (pointCount <= 0) return 0
        return offset.mod(pointCount)
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

        const val EXTRA_SET_ID = "extra_set_id"
        const val ACTION_START_SET = "com.sungyoon.helper.action.START_SET"
        const val ACTION_CANCEL_SET = "com.sungyoon.helper.action.CANCEL_SET"
        const val ACTION_PAUSE_SET = "com.sungyoon.helper.action.PAUSE_SET"
        const val ACTION_RESUME_SET = "com.sungyoon.helper.action.RESUME_SET"

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
