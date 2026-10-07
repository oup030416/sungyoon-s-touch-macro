package com.sungyoon.helper.overlay.pointer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.Button
import com.sungyoon.helper.R
import com.sungyoon.helper.SungyoonHelperService
import com.sungyoon.helper.data.ReservationRuntimeStore
import com.sungyoon.helper.model.ReservationConfig
import com.sungyoon.helper.overlay.set.ReservationInputsView
import com.sungyoon.helper.overlay.set.ReservationProgressState
import com.sungyoon.helper.overlay.set.ReservationProgressView
import com.sungyoon.helper.overlay.set.SetPanelView
import com.sungyoon.helper.overlay.set.setAction
import com.sungyoon.helper.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Connects the shared reservation presentation to the standalone runtime. */
class PointerOverlayReservationPanelView(
    context: Context,
    private val dp: (Int) -> Int
) : SetPanelView(context, context.getString(R.string.reservation_title), {}, context.getString(R.string.reservation_close)) {
    private val inputs = ReservationInputsView(context)
    private val progress = ReservationProgressView(context)
    private var onCloseClick: (() -> Unit)? = null
    private var onStartClick: ((Int, Int, Int) -> Unit)? = null
    private var uiScope: CoroutineScope? = null
    private var runtime: ReservationRuntimeStore.Snapshot? = null
    private var executionBlocked = false
    private val startButton: Button = context.setAction(context.getString(R.string.reservation_start), Color.parseColor("#2E7D32")) {
        when {
            executionBlocked -> toast(context, context.getString(R.string.set_blocked_message))
            runtime?.let { it.active && !it.paused } == true ->
                toast(context, context.getString(R.string.reservation_already_running))
            else -> {
                val config = inputs.readValues() ?: ReservationConfig(
                    inputs.getRunSecondsOrNull()?.coerceIn(1, 3600) ?: 60,
                    inputs.getRestSecondsOrNull()?.coerceIn(1, 3600) ?: 30,
                    inputs.getRepeatCountOrNull()?.coerceIn(1, 9999) ?: 1
                )
                onStartClick?.invoke(config.runSeconds, config.restSeconds, config.repeatCount)
            }
        }
    }
    private val cancelButton = context.setAction(context.getString(R.string.reservation_cancel)) {
        if (runtime?.active == true) {
            context.sendBroadcast(Intent(SungyoonHelperService.ACTION_RESET_RESERVATION).apply {
                setPackage(context.packageName)
            })
        }
    }

    init {
        setOnBackClick {
            inputs.closeIme()
            onCloseClick?.invoke()
        }
        body.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(inputs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        footer.addView(cancelButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        footer.addView(startButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        updateStatusUi()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        uiScope?.cancel()
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope?.launch {
            ReservationRuntimeStore.snapshotFlow(context).collect {
                runtime = it
                updateStatusUi()
            }
        }
        uiScope?.launch {
            while (isActive) {
                if (runtime?.let { it.active && !it.paused } == true) updateStatusUi()
                delay(1000)
            }
        }
    }

    override fun onDetachedFromWindow() {
        inputs.cancelImeRequest()
        uiScope?.cancel()
        uiScope = null
        super.onDetachedFromWindow()
    }

    fun setValuesFromStore(runMin: Int, restMin: Int, repeatCount: Int) =
        inputs.setValues(ReservationConfig(runMin.coerceIn(1, 3600), restMin.coerceIn(1, 3600), repeatCount.coerceIn(1, 9999)))

    fun setOnCloseClick(block: () -> Unit) { onCloseClick = block }
    fun setOnStartClick(block: (runMin: Int, restMin: Int, repeatCount: Int) -> Unit) { onStartClick = block }
    fun setOnRequestIme(block: (Boolean) -> Unit) { inputs.setOnRequestIme(block) }
    fun getRunMinutesOrNull(): Int? = inputs.getRunSecondsOrNull()
    fun getRestMinutesOrNull(): Int? = inputs.getRestSecondsOrNull()
    fun getRepeatCountOrNull(): Int? = inputs.getRepeatCountOrNull()

    fun setExecutionBlocked(blocked: Boolean) {
        executionBlocked = blocked
        updateStatusUi()
    }

    private fun updateStatusUi() {
        val snapshot = runtime
        val active = snapshot?.let { it.active && it.cycleTotal > 0 && (it.paused || it.phaseEndAtMs > 0) } == true
        val paused = snapshot?.paused == true
        val rest = snapshot?.phase == ReservationRuntimeStore.PHASE_REST
        val remaining = if (!active) 0L
            else if (paused) snapshot.pausedRemainingMs
            else (snapshot.phaseEndAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        progress.render(ReservationProgressState(
            active = active,
            paused = paused,
            rest = rest,
            cycleCurrent = snapshot?.cycleCurrent ?: 1,
            cycleTotal = snapshot?.cycleTotal ?: 0,
            remainingMs = remaining,
            phaseDurationMs = (if (rest) snapshot?.restSec else snapshot?.runSec)?.times(1000L) ?: 0L
        ))
        startButton.text = context.getString(when {
            !active -> R.string.reservation_start
            paused -> R.string.reservation_resume
            else -> R.string.reservation_running
        })
        // The guarded listener explains why execution is unavailable instead of swallowing taps.
        startButton.isEnabled = true
        startButton.contentDescription = if (executionBlocked) context.getString(R.string.set_blocked_message) else startButton.text
        startButton.alpha = when {
            executionBlocked -> 0.45f
            active && !paused -> 0.85f
            else -> 1f
        }
        cancelButton.isEnabled = active
        cancelButton.alpha = if (active) 1f else 0.45f
        inputs.setLocked(snapshot?.active == true)
    }
}
