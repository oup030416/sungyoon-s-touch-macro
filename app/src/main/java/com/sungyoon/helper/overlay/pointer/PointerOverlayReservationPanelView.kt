package com.sungyoon.helper.overlay.pointer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.sungyoon.helper.SungyoonHelperService
import com.sungyoon.helper.data.ReservationRuntimeStore
import com.sungyoon.helper.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PointerOverlayReservationPanelView(
    context: Context,
    private val dp: (Int) -> Int
) : LinearLayout(context) {

    private var onCloseClick: (() -> Unit)? = null
    private var onStartClick: ((runMin: Int, restMin: Int, repeatCount: Int) -> Unit)? = null
    private var onRequestIme: ((Boolean) -> Unit)? = null

    private lateinit var closeBtn: Button
    private lateinit var  startBtn: Button
    private lateinit var  runMinEdit: EditText
    private lateinit var  restMinEdit: EditText
    private lateinit var  repeatEdit: EditText
    private lateinit var resetInputsBtn: Button
    private lateinit var headerRowView: View
    private lateinit var bodyContentView: LinearLayout
    private lateinit var bodyScrollView: ScrollView
    private lateinit var footerRowView: View
    private val compactScrollView = ScrollView(context).apply {
        isFillViewport = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    private val compactContainer = LinearLayout(context).apply {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private var compactScrollMode = false
    private var maxViewportHeightPx = 0
    private val preferredPanelHeightPx = dp(560)
    private val compactBottomPaddingPx = dp(28)


    // --- Status UI (Ring + center texts)
    private lateinit var  statusRing: ReservationProgressRingView
    private lateinit var  statusBigText: TextView
    private lateinit var  statusSmallText: TextView

    // --- Runtime snapshot (from ReservationRuntimeStore)
    private var resetBtn: Button? = null

    private var rtActive = false
    private var rtPaused = false
    private var rtPhase = ReservationRuntimeStore.PHASE_RUN
    private var rtPhaseEndAtMs = 0L
    private var rtPausedRemainingMs = 0L
    private var rtCycleCurrent = 1
    private var rtCycleTotal = 0
    private var rtRunMin = 1
    private var rtRestMin = 1

    private var statusReceiver: BroadcastReceiver? = null
    private var statusReceiverRegistered = false
    private var uiScope: CoroutineScope? = null
    // 2) 메시지/헬퍼 추가
    private val lockMsg = "값을 변경하려면 진행 중인 예약을 취소하세요."
    private fun locked(): Boolean = rtActive
    private fun toastLocked() { toast(context, lockMsg) }

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = PointerOverlayDrawables.reservationCardBg(dp)
        isClickable = true
        isFocusable = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(8).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }

        compactScrollView.addView(compactContainer)
        setupLayout()      // ✅ 여기서 헤더/스크롤/하단버튼 구성
        updateStatusUi()   // 초기 상태
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { updateAdaptiveLayoutMode() } }
    }


    private fun setupLayout() {
        headerRowView = buildHeaderRow()
        bodyContentView = buildBodyContent()
        bodyScrollView = buildBodyScrollView()
        footerRowView = buildStartButton()
        rebuildLayout(compact = false)
    }

    private fun buildHeaderRow(): View {
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(context).apply {
            text = "예약"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        closeBtn = Button(context).apply {
            text = "돌아가기 >"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = PointerOverlayDrawables.roundedRippleBg(
                fillColor = Color.parseColor("#3A3A3A"),
                rippleColor = Color.parseColor("#5A5A5A"),
                dp = dp,
                radiusDp = 14
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(40)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { onCloseClick?.invoke() }
        }

        headerRow.addView(titleText)
        headerRow.addView(closeBtn)
        return headerRow
    }

    private fun buildBodyScrollView(): ScrollView {
        return ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(10)
            }
            addView(bodyContentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildBodyContent(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(8))
            addView(buildStatusSection())
            addView(buildInputsSection())
        }
    }

    private fun buildStatusSection(): View {
        val statusWrap = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        statusRing = ReservationProgressRingView(context, dp).apply {
            layoutParams = FrameLayout.LayoutParams(dp(168), dp(168), Gravity.CENTER)
        }

        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        statusBigText = TextView(context).apply {
            text = "예약대기중"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }

        statusSmallText = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#B8B8B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(6), 0, 0)
        }

        textCol.addView(statusBigText)
        textCol.addView(statusSmallText)

        statusWrap.addView(statusRing)
        statusWrap.addView(textCol)

        // 아래 입력 영역과 간격 확보
        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(statusWrap)
            addView(View(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(12))
            })
        }
    }

    private fun buildInputsSection(): View {
        fun smallLabel(text: String): TextView = TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#D7D7D7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        fun inputBg(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#141414"))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#2B2B2B"))
        }

        fun intEdit(widthDp: Int, min: Int, max: Int, maxLen: Int, defaultValue: Int): EditText =
            EditText(context).apply {
                setText(defaultValue.toString())
                setSelection(text?.length ?: 0)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#9E9E9E"))
                hint = defaultValue.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                inputType = InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                background = inputBg()
                setPadding(dp(12), dp(9), dp(12), dp(9))
                layoutParams = LayoutParams(dp(widthDp), LayoutParams.WRAP_CONTENT)
                minHeight = dp(42)
                isFocusableInTouchMode = true
                filters = arrayOf(InputFilter.LengthFilter(maxLen), IntRangeFilter(min, max))
            }

        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            gravity = Gravity.CENTER
        }

        val line2 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            gravity = Gravity.CENTER
        }

        // ✅ 초 단위: 1~3600초(=60분)
        runMinEdit = intEdit(widthDp = 76, min = 1, max = 3600, maxLen = 4, defaultValue = 60)
        restMinEdit = intEdit(widthDp = 76, min = 1, max = 3600, maxLen = 4, defaultValue = 30)
        repeatEdit = intEdit(widthDp = 76, min = 1, max = 9999, maxLen = 4, defaultValue = 1)

        line.addView(runMinEdit)
        line.addView(smallLabel("초 작동").apply { setPadding(dp(8), 0, 0, 0) })

        line.addView(TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(10)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            text = " "
        })

        line.addView(restMinEdit)
        line.addView(smallLabel("초 휴식").apply { setPadding(dp(8), 0, 0, 0) })

        line2.addView(repeatEdit)
        line2.addView(smallLabel("회 반복").apply { setPadding(dp(8), 0, 0, 0) })

        val resetBtnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            gravity = Gravity.CENTER_HORIZONTAL
        }

        resetInputsBtn = Button(context).apply {
            text = "설정 초기화"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = PointerOverlayDrawables.roundedRippleBg(
                fillColor = Color.parseColor("#2A2A2A"),
                rippleColor = Color.parseColor("#4A4A4A"),
                dp = dp,
                radiusDp = 14
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(42)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

            setOnClickListener {
                // 기본값도 초 기준으로
                runMinEdit.setText("60"); runMinEdit.setSelection(2)
                restMinEdit.setText("30"); restMinEdit.setSelection(2)
                repeatEdit.setText("1"); repeatEdit.setSelection(1)
                runMinEdit.clearFocus()
                restMinEdit.clearFocus()
                repeatEdit.clearFocus()
                hideKeyboard(runMinEdit)
                onRequestIme?.invoke(false)
            }
        }

        resetBtnRow.addView(resetInputsBtn)

        setupNumericInputsIme()

        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(line)
            addView(TextView(context))
            addView(line2)
            addView(View(context).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(14)) })
            addView(resetBtnRow)
        }
    }


    private fun buildStartButton(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        startBtn = Button(context).apply {
            text = "예약 작업 시작"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = PointerOverlayDrawables.roundedRippleBg(
                fillColor = Color.parseColor("#2E7D32"),
                rippleColor = Color.parseColor("#66FFFFFF"),
                dp = dp,
                radiusDp = 14
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(46)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

            setOnClickListener {
                // ✅ 이미 진행 중(일시정지 아님)이라면 시작 대신 안내
                if (rtActive && !rtPaused) {
                    com.sungyoon.helper.util.OverlayToast.show(
                        context,
                        "이미 예약이 진행중입니다. '예약 취소' 후 다시 시작하세요."
                    )
                    return@setOnClickListener
                }

                val runSec = runMinEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 60
                val restSec = restMinEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 30
                val repeatCount = repeatEdit.text?.toString()?.toIntOrNull()?.coerceIn(1, 9999) ?: 1

                onStartClick?.invoke(runSec, restSec, repeatCount)
            }
        }

        resetBtn = Button(context).apply {
            text = "예약 취소"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(46)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(10)
            }
            applyResetButtonUi(enabled = false)
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                try {
                    context.sendBroadcast(
                        Intent(SungyoonHelperService.ACTION_RESET_RESERVATION).apply {
                            setPackage(context.packageName)
                        }
                    )
                } catch (_: Throwable) {}
            }
        }

        row.addView(resetBtn)
        row.addView(startBtn)
        return row
    }

    fun setMaxViewportHeight(px: Int) {
        maxViewportHeightPx = px.coerceAtLeast(0)
        post { updateAdaptiveLayoutMode() }
    }

    private fun updateAdaptiveLayoutMode() {
        if (maxViewportHeightPx <= 0) return
        val widthHint = (width - paddingLeft - paddingRight).takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(24))
        val shouldCompact = measureContentHeight(widthHint) > maxViewportHeightPx
        if (shouldCompact != compactScrollMode) {
            rebuildLayout(shouldCompact)
        } else if (!shouldCompact) {
            applyPreferredRegularHeight()
        }
    }

    private fun rebuildLayout(compact: Boolean) {
        compactScrollMode = compact
        removeAllViews()
        detachFromParent(headerRowView)
        detachFromParent(bodyContentView)
        detachFromParent(bodyScrollView)
        detachFromParent(footerRowView)
        detachFromParent(compactScrollView)
        compactContainer.removeAllViews()

        if (compact) {
            compactContainer.setPadding(0, 0, 0, compactBottomPaddingPx)
            compactScrollView.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                maxViewportHeightPx
            )
            headerRowView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            bodyContentView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            footerRowView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            compactContainer.addView(headerRowView)
            compactContainer.addView(bodyContentView)
            compactContainer.addView(footerRowView)
            addView(compactScrollView)
        } else {
            compactContainer.setPadding(0, 0, 0, 0)
            bodyScrollView.removeAllViews()
            bodyContentView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            bodyScrollView.addView(bodyContentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            headerRowView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            bodyScrollView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(10)
            }
            footerRowView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            addView(headerRowView)
            addView(bodyScrollView)
            addView(footerRowView)
            applyPreferredRegularHeight()
        }
        requestLayout()
    }

    private fun applyPreferredRegularHeight() {
        val targetPanelHeight = min(preferredPanelHeightPx, maxViewportHeightPx)
        val fixedHeight = measureRegularFixedHeight()
        val desiredBodyHeight = (targetPanelHeight - fixedHeight).coerceAtLeast(dp(120))
        val current = bodyScrollView.layoutParams as? LayoutParams
        if (current?.height == desiredBodyHeight) return
        bodyScrollView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, desiredBodyHeight).apply {
            topMargin = dp(10)
        }
    }

    private fun measureRegularFixedHeight(): Int {
        val widthHint = (width - paddingLeft - paddingRight).takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(24))
        val childWidthSpec = View.MeasureSpec.makeMeasureSpec(
            widthHint.coerceAtLeast(1),
            View.MeasureSpec.EXACTLY
        )
        val childHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        headerRowView.measure(childWidthSpec, childHeightSpec)
        footerRowView.measure(childWidthSpec, childHeightSpec)
        return paddingTop + paddingBottom + headerRowView.measuredHeight + footerRowView.measuredHeight + dp(10) + dp(10)
    }

    private fun measureContentHeight(widthHint: Int): Int {
        val childWidthSpec = View.MeasureSpec.makeMeasureSpec(
            widthHint.coerceAtLeast(1),
            View.MeasureSpec.EXACTLY
        )
        val childHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        headerRowView.measure(childWidthSpec, childHeightSpec)
        bodyContentView.measure(childWidthSpec, childHeightSpec)
        footerRowView.measure(childWidthSpec, childHeightSpec)
        return paddingTop +
            paddingBottom +
            headerRowView.measuredHeight +
            dp(10) +
            bodyContentView.measuredHeight +
            dp(10) +
            footerRowView.measuredHeight
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }



    private fun applyResetButtonUi(enabled: Boolean) {
        val b = resetBtn ?: return
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.45f

        val fill = if (enabled) Color.parseColor("#5B5CE6") else Color.parseColor("#616161")
        b.background = PointerOverlayDrawables.roundedRippleBg(
            fillColor = fill,
            rippleColor = Color.parseColor("#40FFFFFF"),
            dp = dp,
            radiusDp = 14
        )
    }







    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        uiScope?.cancel()
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        // Runtime store 구독
        uiScope?.launch {
            ReservationRuntimeStore.snapshotFlow(context).collectLatest { snapshot ->
                rtActive = snapshot.active
                rtPaused = snapshot.paused
                rtPhase = snapshot.phase
                rtPhaseEndAtMs = snapshot.phaseEndAtMs
                rtPausedRemainingMs = snapshot.pausedRemainingMs
                rtCycleCurrent = snapshot.cycleCurrent
                rtCycleTotal = snapshot.cycleTotal
                rtRunMin = snapshot.runSec
                rtRestMin = snapshot.restSec
                updateStatusUi()
            }
        }

        // 남은 시간 텍스트 갱신(스토어에 매초 write 안 해도 UI가 자연스럽게 감소)
        uiScope?.launch {
            while (isActive) {
                if (rtActive && !rtPaused) {
                    updateStatusUi()
                }
                delay(1000L)
            }
        }

        // (선택) 브로드캐스트로 즉시 반응
        if (false && !statusReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(SungyoonHelperService.ACTION_RESERVATION_STATUS_CHANGED)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == SungyoonHelperService.ACTION_RESERVATION_STATUS_CHANGED) {
                        // runtime store도 이미 구독 중이지만, 즉시 갱신 트리거로 사용
                        updateStatusUi()
                    }
                }
            }
            statusReceiver = receiver
            try {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                statusReceiverRegistered = true
            } catch (_: Throwable) {
                statusReceiverRegistered = false
                statusReceiver = null
            }
        }
    }

    override fun onDetachedFromWindow() {
        uiScope?.cancel()
        uiScope = null

        if (statusReceiverRegistered) {
            try {
                statusReceiver?.let { context.unregisterReceiver(it) }
            } catch (_: Throwable) {
            } finally {
                statusReceiverRegistered = false
                statusReceiver = null
            }
        }
        super.onDetachedFromWindow()
    }

    fun setValuesFromStore(runMin: Int, restMin: Int, repeatCount: Int) {
        runMinEdit.setText(runMin.coerceIn(1, 3600).toString())
        runMinEdit.setSelection(runMinEdit.text?.length ?: 0)

        restMinEdit.setText(restMin.coerceIn(1, 3600).toString())
        restMinEdit.setSelection(restMinEdit.text?.length ?: 0)

        repeatEdit.setText(repeatCount.coerceIn(1, 9999).toString())
        repeatEdit.setSelection(repeatEdit.text?.length ?: 0)
    }

    fun setOnCloseClick(block: () -> Unit) {
        onCloseClick = block
    }

    fun setOnRequestIme(block: (Boolean) -> Unit) {
        onRequestIme = block
    }

    fun setOnStartClick(block: (runMin: Int, restMin: Int, repeatCount: Int) -> Unit) {
        onStartClick = block
    }

    fun getRunMinutesOrNull(): Int? = runMinEdit.text?.toString()?.toIntOrNull()
    fun getRestMinutesOrNull(): Int? = restMinEdit.text?.toString()?.toIntOrNull()
    fun getRepeatCountOrNull(): Int? = repeatEdit.text?.toString()?.toIntOrNull()

    // ---------------------------
    // Status UI logic
    // ---------------------------
    private fun updateStatusUi(nowMs: Long = System.currentTimeMillis()) {
        // ✅ 기본: start 버튼은 항상 클릭 가능하게 두고, 상태에 따라 텍스트만 바꿈
        startBtn.isEnabled = true
        startBtn.alpha = 1f

        // ✅ 비정상 상태(active인데 endAt이 0 등)도 "대기"로 취급해서 복구 가능하게 함
        val invalidActiveState = (rtActive && rtPhaseEndAtMs <= 0L)
        if (!rtActive || rtCycleTotal <= 0 || invalidActiveState) {
            statusBigText.text = "예약대기중"
            statusSmallText.visibility = View.GONE
            statusRing.setState(
                active = false,
                cycleCurrent = 1,
                cycleTotal = 0,
                phase = ReservationRuntimeStore.PHASE_RUN,
                phaseProgress = 0f
            )

            startBtn.text = "예약 작업 시작"
            applyResetButtonUi(enabled = false)
            applyInputLockUi()
            return
        }

        applyResetButtonUi(enabled = true)

        val phaseName = if (rtPhase == ReservationRuntimeStore.PHASE_REST) "휴식" else "작동"
        statusBigText.text = if (rtPaused) "${phaseName}일시정지" else "${phaseName}중"

        val nextName = if (rtPhase == ReservationRuntimeStore.PHASE_REST) "작동" else "휴식"
        val remainingMs = if (rtPaused) rtPausedRemainingMs else max(0L, rtPhaseEndAtMs - nowMs)
        val remainingSec = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(0)

        statusSmallText.text = "다음 ${nextName}까지 ${remainingSec}초 남음."
        statusSmallText.visibility = View.VISIBLE

        val totalCycles = rtCycleTotal.coerceAtLeast(1)
        val cycleCur = rtCycleCurrent.coerceIn(1, totalCycles)

        val phaseDurationMs =
            ((if (rtPhase == ReservationRuntimeStore.PHASE_REST) rtRestMin else rtRunMin)
                .coerceIn(1, 3600)) * 1000L

        val phaseProgress =
            if (phaseDurationMs > 0L)
                (1f - (remainingMs.toFloat() / phaseDurationMs.toFloat())).coerceIn(0f, 1f)
            else 0f

        statusRing.setState(
            active = true,
            cycleCurrent = cycleCur,
            cycleTotal = totalCycles,
            phase = rtPhase,
            phaseProgress = phaseProgress
        )

        startBtn.text = when {
            rtPaused -> "예약 작업 재개"
            else -> "예약 작업 진행중 (취소 후 재시작)"
        }
        // 진행 중인 상태는 '살짝'만 톤 다운(그래도 클릭 가능)
        startBtn.alpha = if (rtPaused) 1f else 0.85f

        applyInputLockUi()
    }





    // ---------------------------
    // IME handling
    // ---------------------------
    private fun setupNumericInputsIme() {
        setupNumericIme(runMinEdit)
        setupNumericIme(restMinEdit)
        setupNumericIme(repeatEdit)
    }

    private fun applyInputLockUi() {
        val l = locked()

        fun lockEdit(e: EditText) {
            if (l) {
                if (e.isFocused) e.clearFocus()
                hideKeyboard(e)
            }
            // 키보드/입력 자체를 막기 위한 포커스 차단
            e.isFocusable = !l
            e.isFocusableInTouchMode = !l
            e.isCursorVisible = !l
            e.isLongClickable = !l
            e.alpha = if (l) 0.55f else 1f
        }

        if (::runMinEdit.isInitialized) lockEdit(runMinEdit)
        if (::restMinEdit.isInitialized) lockEdit(restMinEdit)
        if (::repeatEdit.isInitialized) lockEdit(repeatEdit)

        if (::resetInputsBtn.isInitialized) {
            // enabled를 false로 두면 터치 자체가 안 들어와서 토스트를 못 띄우므로,
            // enabled는 유지하고, 클릭에서 차단합니다.
            resetInputsBtn.alpha = if (l) 0.55f else 1f
        }
    }

    private fun setupNumericIme(edit: EditText) {
        edit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        edit.setSingleLine(true)

        edit.setOnTouchListener { v, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                if (locked()) { toastLocked(); return@setOnTouchListener true } // ✅ 추가
                onRequestIme?.invoke(true)
                v.post {
                    if (!edit.isFocused) edit.requestFocus()
                    edit.selectAll()
                    showKeyboard(edit)
                }
            }
            false
        }

        edit.setOnClickListener {
            if (locked()) { toastLocked(); return@setOnClickListener } // ✅ 추가
            onRequestIme?.invoke(true)
            edit.post {
                if (!edit.isFocused) edit.requestFocus()
                edit.selectAll()
                showKeyboard(edit)
            }
        }

        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hideKeyboard(edit)
                onRequestIme?.invoke(false)
            }
        }

        edit.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                hideKeyboard(edit)
                onRequestIme?.invoke(false)
                true
            } else false
        }
    }

    private fun showKeyboard(edit: EditText) {
        try {
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Throwable) {
        }
    }

    private fun hideKeyboard(edit: EditText) {
        try {
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------
    // Filters
    // ---------------------------
    private class IntRangeFilter(private val min: Int, private val max: Int) : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val inserted = source?.subSequence(start, end)?.toString().orEmpty()
            val before = dest?.toString().orEmpty()
            val next = buildString {
                append(before.substring(0, dstart))
                append(inserted)
                append(before.substring(dend))
            }
            if (next.isBlank()) return null
            if (!next.all { it.isDigit() }) return ""
            val value = next.toIntOrNull() ?: return ""
            return if (value in min..max) null else ""
        }
    }

    // ---------------------------
    // Ring view
    // ---------------------------
    private class ReservationProgressRingView(
        context: Context,
        private val dp: (Int) -> Int
    ) : View(context) {

        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private val strokePx = dp(14).toFloat()

        private val colorRemain = Color.parseColor("#CFCFCF") // 회색(남은)
        private val colorRun = Color.parseColor("#2F80ED")    // 파랑(작동)
        private val colorRest = Color.parseColor("#9B51E0")   // 보라(휴식)

        private var active = false
        private var cycleCurrent = 1
        private var cycleTotal = 0
        private var phase = ReservationRuntimeStore.PHASE_RUN
        private var phaseProgress = 0f

        fun setState(
            active: Boolean,
            cycleCurrent: Int,
            cycleTotal: Int,
            phase: Int,
            phaseProgress: Float
        ) {
            val nextProg = phaseProgress.coerceIn(0f, 1f)
            val changed =
                this.active != active ||
                        this.cycleCurrent != cycleCurrent ||
                        this.cycleTotal != cycleTotal ||
                        this.phase != phase ||
                        kotlin.math.abs(this.phaseProgress - nextProg) > 0.002f

            this.active = active
            this.cycleCurrent = cycleCurrent
            this.cycleTotal = cycleTotal
            this.phase = phase
            this.phaseProgress = nextProg

            if (changed) invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desired = dp(168)
            val w = resolveSize(desired, widthMeasureSpec)
            val h = resolveSize(desired, heightMeasureSpec)
            val size = min(w, h)
            setMeasuredDimension(size, size)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()
            val pad = strokePx / 2f + dp(2)
            rect.set(pad, pad, w - pad, h - pad)

            paint.strokeWidth = strokePx

            // 기본: 전체 회색 링
            paint.color = colorRemain
            paint.alpha = if (active) 180 else 110
            canvas.drawArc(rect, -90f, 360f, false, paint)

            if (!active || cycleTotal <= 0) return

            val totalSegments = cycleTotal * 2

            // 반복이 너무 크면(수천) 세그먼트로 그리지 않고 연속 진행으로 폴백(성능 보호)
            if (totalSegments > 240) {
                val curSeg =
                    (cycleCurrent - 1).coerceAtLeast(0) * 2 + if (phase == ReservationRuntimeStore.PHASE_REST) 1 else 0
                val completed = curSeg.coerceIn(0, totalSegments)
                val progress01 = ((completed.toFloat() + phaseProgress) / totalSegments.toFloat()).coerceIn(0f, 1f)

                paint.alpha = 255
                paint.color = if (phase == ReservationRuntimeStore.PHASE_REST) colorRest else colorRun
                canvas.drawArc(rect, -90f, 360f * progress01, false, paint)
                return
            }

            val segSweep = 360f / totalSegments.toFloat()
            val gap = 1.4f
            val drawSweep = (segSweep - gap).coerceAtLeast(0.2f)
            val base = -90f

            val curSeg =
                (cycleCurrent - 1).coerceAtLeast(0) * 2 + if (phase == ReservationRuntimeStore.PHASE_REST) 1 else 0
            val completedSeg = curSeg.coerceIn(0, totalSegments - 1)

            for (i in 0 until totalSegments) {
                val segTypeIsRun = (i % 2 == 0)
                val segColor = if (segTypeIsRun) colorRun else colorRest
                val start = base + i * segSweep + gap / 2f

                when {
                    i < completedSeg -> {
                        paint.alpha = 255
                        paint.color = segColor
                        canvas.drawArc(rect, start, drawSweep, false, paint)
                    }

                    i == completedSeg -> {
                        // 현재 세그먼트: 일부는 컬러, 나머지는 회색
                        val coloredSweep = drawSweep * phaseProgress
                        if (coloredSweep > 0.1f) {
                            paint.alpha = 255
                            paint.color = segColor
                            canvas.drawArc(rect, start, coloredSweep, false, paint)
                        }
                        val remainSweep = drawSweep - coloredSweep
                        if (remainSweep > 0.1f) {
                            paint.alpha = 180
                            paint.color = colorRemain
                            canvas.drawArc(rect, start + coloredSweep, remainSweep, false, paint)
                        }
                    }

                    else -> {
                        paint.alpha = 180
                        paint.color = colorRemain
                        canvas.drawArc(rect, start, drawSweep, false, paint)
                    }
                }
            }
        }
    }
}
