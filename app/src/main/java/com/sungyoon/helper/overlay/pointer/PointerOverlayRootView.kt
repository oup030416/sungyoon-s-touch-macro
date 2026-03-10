package com.sungyoon.helper.overlay.pointer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.children
import com.sungyoon.helper.R
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_DRAG
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.util.PointerSizeSpec
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PointerOverlayRootView(context: Context) : FrameLayout(context) {
    enum class Endpoint { START, END }

    private val density = resources.displayMetrics.density

    private val PLAY_RUNNING_FILL_COLOR = Color.parseColor("#334CAF50")
    private val PLAY_STANDBY_FILL_COLOR = Color.parseColor("#664CAF50")

    // 터치 UX: 실제 터치 가능한 영역은 크게 (최소 56dp 고정)
    private val pointerTouchSizePx = dp(56)

    private val dragHandleDrawRadiusPx: Float =
        dp(PointerSizeSpec.radiusDpForLevel(PointerSizeSpec.DEFAULT_LEVEL)).toFloat()
    private var pointerSizeLevel: Int = PointerSizeSpec.DEFAULT_LEVEL
    private var pointerDrawRadiusPx: Float = dragHandleDrawRadiusPx

    private var panelVisible: Boolean = true
    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var onRequestIme: ((Boolean) -> Unit)? = null

    private var onPointerSizeChanged: ((Int) -> Unit)? = null
    private var suppressPointerSizeListener = false
    private var onRandomTouchRadiusChanged: ((Int) -> Unit)? = null
    private var suppressRandomRadiusListener = false

    private var onDeletePointClick: ((String) -> Unit)? = null

    // ✅ 예약 버튼 콜백
    private var onReserveClick: (() -> Unit)? = null
    private var onPresetListClick: (() -> Unit)? = null

    // syncPoints 재구성을 위한 캐시
    private var lastPoints: List<HighlightingPoint> = emptyList()
    private var lastLabelProvider: ((String, Endpoint) -> String)? = null
    private var lastDraggingIds: Set<String> = emptySet()

    private val pointerLayer = FrameLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private val controls = PointerOverlayControlsFactory.create(
        context = context,
        dp = ::dp
    )

    // ✅ 예약 패널(포인터 관리 패널을 덮음)
    private val reservationPanel: PointerOverlayReservationPanelView =
        PointerOverlayReservationPanelView(context, ::dp).apply {
            visibility = View.GONE
            alpha = 0f
            setOnCloseClick { setReservationPanelVisible(false) }
        }

    private val presetPanel: PointerOverlayPresetPanelView =
        PointerOverlayPresetPanelView(context, ::dp).apply {
            visibility = View.GONE
            alpha = 0f
            setOnCloseClick { setPresetPanelVisible(false) }
        }

    private val modalHost = PointerOverlayModalHostView(
        context = context,
        dp = ::dp,
        onRequestIme = ::requestIme
    )

    private val miniPanelToggleBtn: ImageButton = ImageButton(context).apply {
        setImageResource(android.R.drawable.arrow_down_float)
        contentDescription = "패널 표시"
        background = PointerOverlayDrawables.circleRippleBg(
            baseColor = Color.parseColor("#CC121212"),
            rippleColor = Color.parseColor("#33FFFFFF")
        )
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        visibility = View.GONE
        alpha = 0f
    }

    // 선택/이동 보조 UI
    private val moveStickLine: View = View(context).apply {
        visibility = View.GONE
        alpha = 0f
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2).toFloat()
            setColor(Color.parseColor("#B3FFFFFF"))
        }
    }

    private val moveStickHandleSizePx = dp(44)
    private val moveStickLineWidthPx = dp(3)
    private val moveStickDesiredLenPx = dp(70)
    private val moveStickMarginPx = dp(6)
    private val dragLinkThicknessPx = dp(3).toFloat()
    private val dragLinkHeightPx = dp(22)
    private val dragArrowLengthPx = dp(10).toFloat()
    private val dragArrowHalfWidthPx = dp(6).toFloat()
    private val dragLinkInsetMarginPx = dp(2).toFloat()
    private val dragLinkMinVisibleLenPx = dp(10).toFloat()
    private val randomRadiusVisualExtraDp = 5

    private val moveStickHandle: ImageButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_move_24)
        contentDescription = "포인터 이동"
        background = PointerOverlayDrawables.circleRippleBg(
            baseColor = Color.parseColor("#CC5B5CE6"),
            rippleColor = Color.parseColor("#33FFFFFF")
        )
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        visibility = View.GONE
        alpha = 0f
    }

    private val deletePointerBtn: ImageButton = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_delete)
        contentDescription = "포인터 삭제"
        background = PointerOverlayDrawables.circleRippleBg(
            baseColor = Color.parseColor("#22FF5A5A"), // 연한 붉은색
            rippleColor = Color.parseColor("#33FFFFFF")
        )
        imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5A5A"))
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        visibility = View.GONE
        alpha = 0f
    }

    private val views = HashMap<String, DraggablePointerView>() // start handle
    private val dragEndViews = HashMap<String, DraggablePointerView>() // end handle
    private val dragLinkViews = HashMap<String, DragDirectionLinkView>() // start-end connector for drag action
    private val randomRadiusViews = HashMap<String, View>() // tap random radius ring
    private val pointerViewToTarget = HashMap<DraggablePointerView, Pair<String, Endpoint>>()
    private val tmpLoc = IntArray(2)
    private var randomTouchRadiusDp: Int = 5

    private var selectedId: String? = null
    private var selectedEndpoint: Endpoint = Endpoint.START
    private var stickPlaceBelow: Boolean = true

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var handleDragging = false
    private var handleDownRawX = 0f
    private var handleDownRawY = 0f
    private var handleDownCenterX = 0f
    private var handleDownCenterY = 0f

    // drag 콜백 캐시(컨트롤러에서 points 저장/갱신에 사용)
    private var onDragStartInternal: ((String, Endpoint) -> Unit)? = null
    private var onDragMoveInternal: ((String, Endpoint, Float, Float) -> Unit)? = null
    private var onDragEndInternal: ((String, Endpoint, Float, Float) -> Unit)? = null

    init {
        setBackgroundColor(Color.parseColor("#66000000"))

        addView(pointerLayer)
        addView(controls.controlPanel)

        // ✅ controlPanel 위에 예약 패널을 올려 "완전히 가리기"
        addView(reservationPanel)
        addView(presetPanel)

        addView(miniPanelToggleBtn)
        addView(modalHost)

        // move stick / delete button
        pointerLayer.addView(
            moveStickLine,
            FrameLayout.LayoutParams(moveStickLineWidthPx, dp(12)).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        pointerLayer.addView(
            moveStickHandle,
            FrameLayout.LayoutParams(moveStickHandleSizePx, moveStickHandleSizePx).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        pointerLayer.addView(
            deletePointerBtn,
            FrameLayout.LayoutParams(moveStickHandleSizePx, moveStickHandleSizePx).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )

        setSequenceRunning(false)
        setTouchAnimationEnabled(true)

        controls.collapseBtn.setOnClickListener { setControlPanelVisible(false) }
        miniPanelToggleBtn.setOnClickListener { setControlPanelVisible(true) }

        // ✅ 예약 버튼 클릭 → 컨트롤러에서 등록한 콜백 호출(기본 동작: 예약 패널 열기)
        controls.reserveBtn.setOnClickListener { onReserveClick?.invoke() }
        controls.presetListBtn.setOnClickListener { onPresetListClick?.invoke() }

        // controlPanel 레이아웃이 바뀌면(회전/리사이즈) 예약 패널도 즉시 동기화
        controls.controlPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (reservationPanel.visibility == View.VISIBLE) {
                syncReservationPanelLayout(matchHeightToControlPanel = true)
            }
            if (presetPanel.visibility == View.VISIBLE) {
                syncPresetPanelLayout(matchHeightToControlPanel = true)
            }
        }

        setupSecondsIme(controls.intervalEdit)
        setupSecondsIme(controls.dragDurationEdit)
        setupRandomRadiusSeek()
        setupMoveStickHandleDrag()

        deletePointerBtn.setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            clearSelection()
            onDeletePointClick?.invoke(id)
        }

        post { applyResponsiveLayout(width, height) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (controls.intervalEdit.hasFocus() &&
                !isTouchInsideViewRaw(ev.rawX, ev.rawY, controls.intervalEdit)
            ) {
                controls.intervalEdit.clearFocus()
                hideKeyboard()
                requestIme(false)
            }
            if (controls.dragDurationEdit.hasFocus() &&
                !isTouchInsideViewRaw(ev.rawX, ev.rawY, controls.dragDurationEdit)
            ) {
                controls.dragDurationEdit.clearFocus()
                hideKeyboard()
                requestIme(false)
            }

            val touchedPointerTarget = findPointerTargetAtRaw(ev.rawX, ev.rawY)
            val touchedHandle = isTouchInsideViewRaw(ev.rawX, ev.rawY, moveStickHandle)
            val touchedDelete = isTouchInsideViewRaw(ev.rawX, ev.rawY, deletePointerBtn)

            // ✅ 예약 패널이 떠있을 땐 예약 패널도 "패널 영역"으로 간주
            val touchedPanel =
                isTouchInsideViewRaw(ev.rawX, ev.rawY, controls.controlPanel) ||
                        (reservationPanel.visibility == View.VISIBLE && isTouchInsideViewRaw(ev.rawX, ev.rawY, reservationPanel)) ||
                        (presetPanel.visibility == View.VISIBLE && isTouchInsideViewRaw(ev.rawX, ev.rawY, presetPanel)) ||
                        (modalHost.isShowing() && isTouchInsideViewRaw(ev.rawX, ev.rawY, modalHost))

            val touchedMini = (miniPanelToggleBtn.visibility == View.VISIBLE) &&
                    isTouchInsideViewRaw(ev.rawX, ev.rawY, miniPanelToggleBtn)

            if (touchedPointerTarget == null && !touchedHandle && !touchedDelete && !touchedPanel && !touchedMini) {
                clearSelection()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun setReservationValuesFromStore(runMin: Int, restMin: Int, repeatCount: Int) {
        // 이제 runMin/restMin은 "초" 값이 들어옵니다.
        reservationPanel.setValuesFromStore(runMin, restMin, repeatCount)
    }

    fun setOnDeletePointClick(block: (String) -> Unit) {
        onDeletePointClick = block
    }

    // ✅ 컨트롤러가 예약 버튼 동작을 연결할 수 있도록
    fun setOnReserveClick(block: () -> Unit) {
        onReserveClick = block
    }

    fun setOnPresetListClick(block: () -> Unit) {
        onPresetListClick = block
    }

    private var onControlPanelVisibleChanged: ((Boolean) -> Unit)? = null
    private var onReservationPanelVisibleChanged: ((Boolean) -> Unit)? = null
    private var onPresetPanelVisibleChanged: ((Boolean) -> Unit)? = null


    fun setOnControlPanelVisibleChanged(block: (Boolean) -> Unit) {
        onControlPanelVisibleChanged = block
    }

    fun setOnReservationPanelVisibleChanged(block: (Boolean) -> Unit) {
        onReservationPanelVisibleChanged = block
    }

    fun setOnPresetPanelVisibleChanged(block: (Boolean) -> Unit) {
        onPresetPanelVisibleChanged = block
    }

    // ✅ 컨트롤러에서 상태 저장용으로 읽기 API
    fun isControlPanelVisible(): Boolean = panelVisible
    fun isReservationPanelVisible(): Boolean = reservationPanel.visibility == View.VISIBLE
    fun isPresetPanelVisible(): Boolean = presetPanel.visibility == View.VISIBLE

    // ✅ 컨트롤러에서 복원 적용용
    fun setControlPanelVisibleFromController(visible: Boolean) {
        setControlPanelVisible(visible)
    }
    // ✅ 외부(컨트롤러)에서 호출하기 편한 API
    fun openReservationPanel() {
        setReservationPanelVisible(true)
    }

    fun closeReservationPanel() {
        setReservationPanelVisible(false)
    }

    fun openPresetPanel() {
        setPresetPanelVisible(true)
    }

    fun closePresetPanel() {
        setPresetPanelVisible(false)
    }

    fun setPresetEntries(entries: List<PresetEntry>) {
        presetPanel.setPresets(entries)
    }

    fun getSelectedPresetId(): String? = presetPanel.getSelectedPresetId()

    fun setSelectedPresetId(presetId: String?) {
        presetPanel.setSelectedPresetId(presetId)
    }

    fun setOnPresetAddCurrentClick(block: () -> Unit) {
        presetPanel.setOnAddCurrentClick(block)
    }

    fun setOnPresetDeleteClick(block: (String) -> Unit) {
        presetPanel.setOnDeleteClick(block)
    }

    fun setOnPresetLoadClick(block: (String) -> Unit) {
        presetPanel.setOnLoadClick(block)
    }

    fun setOnPresetRenameClick(block: (PresetEntry) -> Unit) {
        presetPanel.setOnRenameClick(block)
    }

    fun showConfirmationDialog(
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        onConfirm: () -> Unit
    ) {
        modalHost.showConfirmationDialog(
            title = title,
            message = message,
            confirmText = confirmText,
            cancelText = cancelText,
            onConfirm = onConfirm
        )
    }

    fun showInputDialog(
        title: String,
        initialValue: String,
        hint: String,
        confirmText: String,
        cancelText: String,
        onSubmit: (String) -> Unit
    ) {
        modalHost.showInputDialog(
            title = title,
            initialValue = initialValue,
            hint = hint,
            confirmText = confirmText,
            cancelText = cancelText,
            onSubmit = onSubmit
        )
    }

    private fun setReservationPanelVisible(visible: Boolean) {
        val wasVisible = reservationPanel.visibility == View.VISIBLE
        if (wasVisible == visible) return

        if (visible) {
            // 패널이 숨김 상태면 먼저 보이게
            if (!panelVisible) setControlPanelVisible(true)

            if (!panelVisible) setControlPanelVisible(true)
            closePresetPanel()
            syncReservationPanelLayout(matchHeightToControlPanel = true)

            reservationPanel.visibility = View.VISIBLE
            reservationPanel.alpha = 0f
            reservationPanel.scaleX = 0.98f
            reservationPanel.scaleY = 0.98f
            reservationPanel.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(60L)
                .start()
        } else {
            reservationPanel.animate().cancel()
            if (reservationPanel.visibility == View.VISIBLE) {
                reservationPanel.animate()
                    .alpha(0f).scaleX(0.98f).scaleY(0.98f)
                    .setDuration(60L)
                    .withEndAction { reservationPanel.visibility = View.GONE }
                    .start()
            } else {
                reservationPanel.visibility = View.GONE
                reservationPanel.alpha = 0f
            }
        }

        // ✅ 추가: 예약 패널 표시 상태 저장용 콜백
        onReservationPanelVisibleChanged?.invoke(visible)
    }

    private fun setPresetPanelVisible(visible: Boolean) {
        val wasVisible = presetPanel.visibility == View.VISIBLE
        if (wasVisible == visible) return

        if (visible) {
            if (!panelVisible) setControlPanelVisible(true)
            closeReservationPanel()
            syncPresetPanelLayout(matchHeightToControlPanel = true)

            presetPanel.visibility = View.VISIBLE
            presetPanel.alpha = 0f
            presetPanel.scaleX = 0.98f
            presetPanel.scaleY = 0.98f
            presetPanel.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(60L)
                .start()
        } else {
            presetPanel.animate().cancel()
            if (presetPanel.visibility == View.VISIBLE) {
                presetPanel.animate()
                    .alpha(0f).scaleX(0.98f).scaleY(0.98f)
                    .setDuration(60L)
                    .withEndAction { presetPanel.visibility = View.GONE }
                    .start()
            } else {
                presetPanel.visibility = View.GONE
                presetPanel.alpha = 0f
            }
        }

        onPresetPanelVisibleChanged?.invoke(visible)
    }


    private fun syncReservationPanelLayout(matchHeightToControlPanel: Boolean) {
        syncOverlayPanelLayout(reservationPanel, matchHeightToControlPanel)
    }

    private fun syncPresetPanelLayout(matchHeightToControlPanel: Boolean) {
        syncOverlayPanelLayout(presetPanel, matchHeightToControlPanel)
    }

    private fun syncOverlayPanelLayout(panel: View, matchHeightToControlPanel: Boolean) {
        val src = controls.controlPanel.layoutParams as? FrameLayout.LayoutParams ?: return

        val desiredH = if (matchHeightToControlPanel && controls.controlPanel.height > 0) {
            controls.controlPanel.height
        } else {
            src.height
        }

        val lp = FrameLayout.LayoutParams(src.width, desiredH).apply {
            gravity = src.gravity
            leftMargin = src.leftMargin
            topMargin = src.topMargin
            rightMargin = src.rightMargin
            bottomMargin = src.bottomMargin
        }
        panel.layoutParams = lp

        if (matchHeightToControlPanel && controls.controlPanel.height > 0) {
            panel.minimumHeight = controls.controlPanel.height
        }
    }

    fun setPointerSizeLevel(level: Int) {
        val next = level.coerceIn(PointerSizeSpec.MIN_LEVEL, PointerSizeSpec.MAX_LEVEL)
        pointerSizeLevel = next
        pointerDrawRadiusPx = dp(PointerSizeSpec.radiusDpForLevel(next)).toFloat()
        // Removed pointer size text binding.

        suppressPointerSizeListener = true
        try {
            controls.pointerSizeSeek.progress = next - 1
        } finally {
            suppressPointerSizeListener = false
        }

        // ✅ 재생성 없이 기존 포인터들의 draw radius만 갱신
        for (v in views.values) {
            v.setDrawRadiusPx(pointerDrawRadiusPx)
        }
        for (v in dragEndViews.values) {
            v.setDrawRadiusPx(pointerDrawRadiusPx)
        }

        updateMoveStickPosition()
    }

    private fun requestIme(enable: Boolean) {
        onRequestIme?.invoke(enable)
    }

    private fun setupSecondsIme(edit: EditText) {
        edit.imeOptions = EditorInfo.IME_ACTION_DONE
        edit.setSingleLine(true)

        edit.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                requestIme(true)
                v.post {
                    if (!edit.isFocused) edit.requestFocus()
                    edit.selectAll()
                    imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            false
        }

        edit.setOnClickListener {
            requestIme(true)
            edit.post {
                if (!edit.isFocused) edit.requestFocus()
                edit.selectAll()
                imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hideKeyboard()
                requestIme(false)
            }
        }

        edit.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                hideKeyboard()
                requestIme(false)
                true
            } else false
        }
    }

    private fun setupPointerSizeSeek() {
        controls.pointerSizeSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (suppressPointerSizeListener) return
                val level = (progress + 1).coerceIn(1, 10)

                // UI/포인터 즉시 반영
                setPointerSizeLevel(level)

                // 저장 요청(컨트롤러에서 처리)
                if (fromUser) {
                    onPointerSizeChanged?.invoke(level)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun setupRandomRadiusSeek() {
        controls.randomRadiusSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (suppressRandomRadiusListener) return
                val clamped = progress.coerceIn(0, 20)
                randomTouchRadiusDp = clamped
                controls.randomRadiusValueText.text =
                    context.getString(R.string.pointer_random_radius_value, clamped)
                updateAllTapPointerRadii()
                refreshRandomRadiusViews()
                if (fromUser) {
                    onRandomTouchRadiusChanged?.invoke(clamped)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun hideKeyboard() {
        try {
            val token = controls.intervalEdit.windowToken
                ?: controls.dragDurationEdit.windowToken
                ?: windowToken
            if (token != null) {
                imm.hideSoftInputFromWindow(token, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isTouchInsideViewRaw(rawX: Float, rawY: Float, target: View): Boolean {
        if (target.visibility != View.VISIBLE || target.width <= 0 || target.height <= 0) return false
        target.getLocationOnScreen(tmpLoc)
        val left = tmpLoc[0]
        val top = tmpLoc[1]
        val right = left + target.width
        val bottom = top + target.height
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }
    private fun setControlPanelVisible(visible: Boolean) {
        if (panelVisible == visible) return
        panelVisible = visible

        if (visible) {
            controls.controlPanel.visibility = View.VISIBLE
            controls.controlPanel.alpha = 0f
            controls.controlPanel.scaleX = 0.96f
            controls.controlPanel.scaleY = 0.96f
            controls.controlPanel.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(130L)
                .start()

            miniPanelToggleBtn.animate().cancel()
            miniPanelToggleBtn.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction { miniPanelToggleBtn.visibility = View.GONE }
                .start()
        } else {
            // ✅ 패널을 숨길 때 예약 패널도 같이 닫음
            closeReservationPanel()

            closePresetPanel()
            modalHost.dismiss()

            if (controls.intervalEdit.hasFocus()) {
                controls.intervalEdit.clearFocus()
                hideKeyboard()
                requestIme(false)
            }
            if (controls.dragDurationEdit.hasFocus()) {
                controls.dragDurationEdit.clearFocus()
                hideKeyboard()
                requestIme(false)
            }

            controls.controlPanel.animate().cancel()
            controls.controlPanel.animate()
                .alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(120L)
                .withEndAction { controls.controlPanel.visibility = View.GONE }
                .start()

            miniPanelToggleBtn.visibility = View.VISIBLE
            miniPanelToggleBtn.alpha = 0f
            miniPanelToggleBtn.animate()
                .alpha(1f)
                .setDuration(120L)
                .start()
        }

        // ✅ 추가: 터치 패널(컨트롤 패널) 표시 상태 저장용 콜백
        onControlPanelVisibleChanged?.invoke(panelVisible)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyResponsiveLayout(w, h)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            resyncCachedPointsForCurrentLayout()
        }
        dragLinkViews.keys.toList().forEach { updateDragLinkForPoint(it) }
        randomRadiusViews.keys.toList().forEach { updateRandomRadiusForPoint(it) }
        updateMoveStickPosition()
    }

    private fun resyncCachedPointsForCurrentLayout() {
        val labelProvider = lastLabelProvider ?: return
        val onDragStart = onDragStartInternal ?: return
        val onDragMove = onDragMoveInternal ?: return
        val onDragEnd = onDragEndInternal ?: return
        syncPoints(
            points = lastPoints,
            labelProvider = labelProvider,
            draggingIds = lastDraggingIds,
            onDragStart = onDragStart,
            onDragMove = onDragMove,
            onDragEnd = onDragEnd
        )
    }

    fun setOnRepeatToggleClick(block: () -> Unit) {
        controls.repeatToggleBtn.setOnClickListener { block() }
    }

/*
    fun setRepeatEnabled(enabled: Boolean) {
        controls.repeatToggleBtn.text = if (enabled) "반복 실행 ON" else "반복 실행 OFF"
        controls.repeatToggleBtn.contentDescription = if (enabled) "반복 실행 켜짐" else "반복 실행 꺼짐"
        controls.repeatToggleBtn.background = PointerOverlayDrawables.roundedRippleBg(
            fillColor = if (enabled) Color.parseColor("#4D5B5CE6") else Color.parseColor("#2FFFFFFF"),
            rippleColor = Color.parseColor("#33FFFFFF"),
            dp = ::dp,
            radiusDp = 14
        )
    }

    fun setTouchAnimationEnabled(enabled: Boolean) {
        controls.touchAnimToggleBtn.text = if (enabled) "터치 애니메이션 ON" else "터치 애니메이션 OFF"
        controls.touchAnimToggleBtn.contentDescription =
            if (enabled) "터치 애니메이션 켜짐" else "터치 애니메이션 꺼짐"
        controls.touchAnimToggleBtn.background = PointerOverlayDrawables.roundedRippleBg(
            fillColor = if (enabled) PLAY_STANDBY_FILL_COLOR else Color.parseColor("#2FFFFFFF"),
            rippleColor = Color.parseColor("#33FFFFFF"),
            dp = ::dp,
            radiusDp = 14
        )
    }

    fun getPointerLayerOffsetOnScreen(): Pair<Int, Int> {
        pointerLayer.getLocationOnScreen(tmpLoc)
        return tmpLoc[0] to tmpLoc[1]
    }

    fun localCenterToScreen(centerX: Float, centerY: Float): Pair<Float, Float> {
        pointerLayer.getLocationOnScreen(tmpLoc)
        return (tmpLoc[0] + centerX) to (tmpLoc[1] + centerY)
    }

    fun screenCenterToLocal(screenX: Float, screenY: Float): Pair<Float, Float> {
        pointerLayer.getLocationOnScreen(tmpLoc)
        return (screenX - tmpLoc[0]) to (screenY - tmpLoc[1])
    }

    fun setOnAddClick(block: () -> Unit) {
        controls.addBtn.setOnClickListener { block() }
    }

    fun setOnAddDragClick(block: () -> Unit) {
        controls.addDragBtn.setOnClickListener { block() }
    }

    fun setOnPresetListButtonClick(block: () -> Unit) {
        controls.presetListBtn.setOnClickListener { block() }
    }

    fun setOnCloseClick(block: () -> Unit) {
        controls.closeBtn.setOnClickListener { block() }
    }

    fun setOnPlayToggleClick(block: () -> Unit) {
        controls.playToggleBtn.setOnClickListener { block() }
    }

    fun setOnTouchAnimationToggleClick(block: () -> Unit) {
        controls.touchAnimToggleBtn.setOnClickListener { block() }
    }

    fun setSequenceRunning(running: Boolean) {
        controls.playToggleBtn.text = if (running) "■" else "▶"
        val fill = if (running) PLAY_RUNNING_FILL_COLOR else PLAY_STANDBY_FILL_COLOR
        controls.playToggleBtn.background = PointerOverlayDrawables.roundedRippleBg(
            fillColor = fill,
            rippleColor = Color.parseColor("#33FFFFFF"),
            dp = ::dp,
            radiusDp = 14
        )
        controls.playToggleBtn.contentDescription = if (running) "정지" else "재생"
    }

*/

    fun setRepeatEnabled(enabled: Boolean) {
        controls.repeatToggleBtn.text =
            context.getString(if (enabled) R.string.pointer_repeat_on else R.string.pointer_repeat_off)
        controls.repeatToggleBtn.contentDescription =
            context.getString(if (enabled) R.string.pointer_repeat_desc_on else R.string.pointer_repeat_desc_off)
        controls.repeatToggleBtn.background = PointerOverlayDrawables.roundedRippleBg(
            fillColor = if (enabled) Color.parseColor("#4D5B5CE6") else Color.parseColor("#2FFFFFFF"),
            rippleColor = Color.parseColor("#33FFFFFF"),
            dp = ::dp,
            radiusDp = 14
        )
    }

    fun setTouchAnimationEnabled(enabled: Boolean) {
        controls.touchAnimToggleBtn.text =
            context.getString(if (enabled) R.string.pointer_touch_animation_on else R.string.pointer_touch_animation_off)
        controls.touchAnimToggleBtn.contentDescription =
            context.getString(
                if (enabled) R.string.pointer_touch_animation_desc_on
                else R.string.pointer_touch_animation_desc_off
            )
        controls.touchAnimToggleBtn.background = PointerOverlayDrawables.roundedRippleBg(
            fillColor = if (enabled) PLAY_STANDBY_FILL_COLOR else Color.parseColor("#2FFFFFFF"),
            rippleColor = Color.parseColor("#33FFFFFF"),
            dp = ::dp,
            radiusDp = 14
        )
    }

    fun getPointerLayerOffsetOnScreen(): Pair<Int, Int> {
        pointerLayer.getLocationOnScreen(tmpLoc)
        return tmpLoc[0] to tmpLoc[1]
    }

    fun localCenterToScreen(centerX: Float, centerY: Float): Pair<Float, Float> {
        pointerLayer.getLocationOnScreen(tmpLoc)
        return (tmpLoc[0] + centerX) to (tmpLoc[1] + centerY)
    }

    fun screenCenterToLocal(screenX: Float, screenY: Float): Pair<Float, Float> {
        pointerLayer.getLocationOnScreen(tmpLoc)
        return (screenX - tmpLoc[0]) to (screenY - tmpLoc[1])
    }

    fun setOnAddClick(block: () -> Unit) {
        controls.addBtn.setOnClickListener { block() }
    }

    fun setOnAddDragClick(block: () -> Unit) {
        controls.addDragBtn.setOnClickListener { block() }
    }

    fun setOnPresetListButtonClick(block: () -> Unit) {
        controls.presetListBtn.setOnClickListener { block() }
    }

    fun setOnCloseClick(block: () -> Unit) {
        controls.closeBtn.setOnClickListener { block() }
    }

    fun setOnPlayToggleClick(block: () -> Unit) {
        controls.playToggleBtn.setOnClickListener { block() }
    }

    fun setOnTouchAnimationToggleClick(block: () -> Unit) {
        controls.touchAnimToggleBtn.setOnClickListener { block() }
    }

    fun setSequenceRunning(running: Boolean) {
        controls.playToggleBtn.text = if (running) "■" else "▶"
        val fill = if (running) PLAY_RUNNING_FILL_COLOR else PLAY_STANDBY_FILL_COLOR
        controls.playToggleBtn.background = PointerOverlayDrawables.roundedRippleBg(
            fillColor = fill,
            rippleColor = Color.parseColor("#33FFFFFF"),
            dp = ::dp,
            radiusDp = 14
        )
        controls.playToggleBtn.contentDescription =
            context.getString(if (running) R.string.pointer_play_desc_stop else R.string.pointer_play_desc_start)
    }

    fun setTapIntervalSeconds(seconds: Float) {
        val s = String.format(Locale.US, "%.1f", seconds)
        if (controls.intervalEdit.text?.toString() != s) {
            controls.intervalEdit.setText(s)
            controls.intervalEdit.setSelection(s.length)
        }
    }

    fun setOnTapIntervalChanged(block: (Float) -> Unit) {
        controls.intervalEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val raw = s?.toString().orEmpty()
                if (raw.isBlank() || raw.endsWith(".")) return
                val v = raw.toFloatOrNull() ?: return
                val clamped = v.coerceAtLeast(0.1f)
                block(clamped)
            }
        })
    }

    fun getTapIntervalSecondsOrNull(): Float? {
        val raw = controls.intervalEdit.text?.toString().orEmpty()
        if (raw.isBlank() || raw.endsWith(".")) return null
        return raw.toFloatOrNull()
    }

    fun setDragDurationSeconds(seconds: Float) {
        val s = String.format(Locale.US, "%.1f", seconds)
        if (controls.dragDurationEdit.text?.toString() != s) {
            controls.dragDurationEdit.setText(s)
            controls.dragDurationEdit.setSelection(s.length)
        }
    }

    fun setOnDragDurationChanged(block: (Float) -> Unit) {
        controls.dragDurationEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val raw = s?.toString().orEmpty()
                if (raw.isBlank() || raw.endsWith(".")) return
                val v = raw.toFloatOrNull() ?: return
                val clamped = v.coerceIn(0.1f, 10.0f)
                block(clamped)
            }
        })
    }

    fun getDragDurationSecondsOrNull(): Float? {
        val raw = controls.dragDurationEdit.text?.toString().orEmpty()
        if (raw.isBlank() || raw.endsWith(".")) return null
        return raw.toFloatOrNull()
    }

    private fun tapPointerRadiusDp(): Int = if (randomTouchRadiusDp <= 0) 10 else 0

    private fun tapPointerDrawRadiusPx(): Float = tapPointerRadiusDp() * density

    private fun updateAllTapPointerRadii() {
        val tapRadiusPx = tapPointerDrawRadiusPx()
        for (point in lastPoints) {
            if (point.actionType != ACTION_TYPE_DRAG) {
                views[point.id]?.setDrawRadiusPx(tapRadiusPx)
            }
        }
        updateMoveStickPosition()
    }

    fun setRandomTouchRadiusDp(value: Int) {
        val clamped = value.coerceIn(0, 20)
        randomTouchRadiusDp = clamped
        controls.randomRadiusValueText.text =
            context.getString(R.string.pointer_random_radius_value, clamped)
        suppressRandomRadiusListener = true
        try {
            controls.randomRadiusSeek.progress = clamped
        } finally {
            suppressRandomRadiusListener = false
        }
        updateAllTapPointerRadii()
        refreshRandomRadiusViews()
    }

    fun setOnRandomTouchRadiusChanged(block: (Int) -> Unit) {
        onRandomTouchRadiusChanged = block
    }

    fun getRandomTouchRadiusDpOrNull(): Int? {
        return randomTouchRadiusDp
    }

    fun syncPoints(
        points: List<HighlightingPoint>,
        labelProvider: (String, Endpoint) -> String,
        draggingIds: Set<String>,
        onDragStart: (String, Endpoint) -> Unit,
        onDragMove: (String, Endpoint, Float, Float) -> Unit,
        onDragEnd: (String, Endpoint, Float, Float) -> Unit
    ) {
        // cache
        lastPoints = points
        lastLabelProvider = labelProvider
        lastDraggingIds = draggingIds

        // cache callbacks
        onDragStartInternal = onDragStart
        onDragMoveInternal = onDragMove
        onDragEndInternal = onDragEnd

        val ids = points.map { it.id }.toHashSet()
        val dragIds = points.asSequence()
            .filter { it.actionType == ACTION_TYPE_DRAG }
            .map { it.id }
            .toHashSet()
        val tapIds = points.asSequence()
            .filter { it.actionType != ACTION_TYPE_DRAG }
            .map { it.id }
            .toHashSet()

        val iter = views.entries.iterator()
        while (iter.hasNext()) {
            val (id, view) = iter.next()
            if (!ids.contains(id)) {
                pointerLayer.removeView(view)
                pointerViewToTarget.remove(view)
                iter.remove()
                removeRandomRadiusForPoint(id)
                if (selectedId == id) {
                    clearSelection()
                }
            }
        }

        val endIter = dragEndViews.entries.iterator()
        while (endIter.hasNext()) {
            val (id, view) = endIter.next()
            if (!dragIds.contains(id)) {
                pointerLayer.removeView(view)
                pointerViewToTarget.remove(view)
                endIter.remove()
                if (selectedId == id && selectedEndpoint == Endpoint.END) {
                    clearSelection()
                }
            }
        }

        val lineIter = dragLinkViews.entries.iterator()
        while (lineIter.hasNext()) {
            val (id, line) = lineIter.next()
            if (!dragIds.contains(id)) {
                pointerLayer.removeView(line)
                lineIter.remove()
            }
        }

        val radiusIter = randomRadiusViews.entries.iterator()
        while (radiusIter.hasNext()) {
            val (id, view) = radiusIter.next()
            if (!tapIds.contains(id) || randomTouchRadiusDp <= 0) {
                pointerLayer.removeView(view)
                radiusIter.remove()
            }
        }

        fun ensureHandleView(pointId: String, endpoint: Endpoint): DraggablePointerView {
            val map = if (endpoint == Endpoint.START) views else dragEndViews
            val existing = map[pointId]
            if (existing != null) return existing

            val handle = DraggablePointerView(
                context = context,
                sizePx = pointerTouchSizePx,
                drawRadiusPx = dragHandleDrawRadiusPx
            ).apply {
                isClickable = true
                isFocusable = false

                var downRawX = 0f
                var downRawY = 0f
                var downCenterX = 0f
                var downCenterY = 0f
                var dragging = false

                setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = ev.rawX
                            downRawY = ev.rawY
                            downCenterX = getCenterX()
                            downCenterY = getCenterY()
                            dragging = false
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = ev.rawX - downRawX
                            val dy = ev.rawY - downRawY
                            if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                dragging = true
                                selectPointer(pointId, endpoint)
                                onDragStartInternal?.invoke(pointId, endpoint)
                            }
                            if (dragging) {
                                val newCx = downCenterX + dx
                                val newCy = downCenterY + dy
                                moveSelectedPointerToLocalCenter(
                                    id = pointId,
                                    endpoint = endpoint,
                                    cx = newCx,
                                    cy = newCy,
                                    notifyMove = true
                                )
                            }
                            true
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (dragging) {
                                val current = if (endpoint == Endpoint.START) views[pointId] else dragEndViews[pointId]
                                val cx = current?.getCenterX() ?: getCenterX()
                                val cy = current?.getCenterY() ?: getCenterY()
                                onDragEndInternal?.invoke(pointId, endpoint, cx, cy)

                                val ph = pointerLayer.height
                                stickPlaceBelow = if (ph > 0) {
                                    cy < (ph / 2f)
                                } else true
                                updateMoveStickPosition(forceShow = true)
                            } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                                selectPointer(pointId, endpoint)
                            }
                            dragging = false
                            true
                        }

                        else -> true
                    }
                }
            }

            map[pointId] = handle
            pointerViewToTarget[handle] = pointId to endpoint
            pointerLayer.addView(
                handle,
                FrameLayout.LayoutParams(pointerTouchSizePx, pointerTouchSizePx).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
            )
            return handle
        }

        for (p in points) {
            val startView = ensureHandleView(p.id, Endpoint.START)
            val startRadius = if (p.actionType == ACTION_TYPE_DRAG) {
                dragHandleDrawRadiusPx
            } else {
                tapPointerDrawRadiusPx()
            }
            startView.setDrawRadiusPx(startRadius)
            startView.setLabel(labelProvider(p.id, Endpoint.START))
            val draggingStart = draggingIds.contains("${p.id}:start")
            if (!draggingStart && !(handleDragging && selectedId == p.id && selectedEndpoint == Endpoint.START)) {
                val (sx, sy) = screenCenterToLocal(p.x, p.y)
                startView.setCenter(sx, sy)
            }

            if (p.actionType == ACTION_TYPE_DRAG) {
                removeRandomRadiusForPoint(p.id)
                val endView = ensureHandleView(p.id, Endpoint.END)
                endView.setDrawRadiusPx(dragHandleDrawRadiusPx)
                endView.setLabel(labelProvider(p.id, Endpoint.END))
                val draggingEnd = draggingIds.contains("${p.id}:end")
                if (!draggingEnd && !(handleDragging && selectedId == p.id && selectedEndpoint == Endpoint.END)) {
                    val (ex, ey) = screenCenterToLocal(p.dragToX, p.dragToY)
                    endView.setCenter(ex, ey)
                }
                updateDragLinkForPoint(p.id)
            } else {
                val end = dragEndViews.remove(p.id)
                if (end != null) {
                    pointerLayer.removeView(end)
                    pointerViewToTarget.remove(end)
                }
                removeDragLinkForPoint(p.id)
                if (selectedId == p.id && selectedEndpoint == Endpoint.END) {
                    selectedEndpoint = Endpoint.START
                }
                updateRandomRadiusForPoint(p.id)
            }
        }

        updateMoveStickPosition()
    }

    private fun ensureDragLinkForPoint(pointId: String): DragDirectionLinkView {
        dragLinkViews[pointId]?.let { return it }

        val v = DragDirectionLinkView(
            context = context,
            lineThicknessPx = dragLinkThicknessPx,
            arrowLengthPx = dragArrowLengthPx,
            arrowHalfWidthPx = dragArrowHalfWidthPx,
            color = Color.parseColor("#CCFFFFFF")
        ).apply {
            alpha = 0.85f
        }
        pointerLayer.addView(
            v,
            0,
            FrameLayout.LayoutParams(dp(2), dragLinkHeightPx).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        dragLinkViews[pointId] = v
        return v
    }

    private fun removeDragLinkForPoint(pointId: String) {
        val v = dragLinkViews.remove(pointId) ?: return
        pointerLayer.removeView(v)
    }

    private fun updateDragLinkForPoint(pointId: String) {
        val start = views[pointId] ?: return
        val end = dragEndViews[pointId] ?: run {
            removeDragLinkForPoint(pointId)
            return
        }
        val line = ensureDragLinkForPoint(pointId)

        val sx = start.getCenterX()
        val sy = start.getCenterY()
        val ex = end.getCenterX()
        val ey = end.getCenterY()

        val dx = ex - sx
        val dy = ey - sy
        val len = max(1f, hypot(dx, dy))
        if (len < dragLinkMinVisibleLenPx) {
            line.visibility = View.GONE
            return
        }

        val desiredStartInset = dragHandleDrawRadiusPx + dragLinkInsetMarginPx
        val desiredEndInset = dragHandleDrawRadiusPx + dragLinkInsetMarginPx
        val maxInsetSum = (len - dragLinkMinVisibleLenPx).coerceAtLeast(0f)
        val desiredInsetSum = desiredStartInset + desiredEndInset
        val insetScale = if (desiredInsetSum <= 0f || desiredInsetSum <= maxInsetSum) {
            1f
        } else {
            maxInsetSum / desiredInsetSum
        }
        val startInset = desiredStartInset * insetScale
        val endInset = desiredEndInset * insetScale
        line.setInsets(startInsetPx = startInset, endInsetPx = endInset)
        line.visibility = View.VISIBLE

        val lp = (line.layoutParams as FrameLayout.LayoutParams).apply {
            width = len.roundToInt()
            height = dragLinkHeightPx
            gravity = Gravity.TOP or Gravity.START
        }
        line.layoutParams = lp
        val halfLinkHeight = dragLinkHeightPx / 2f
        line.pivotX = 0f
        line.pivotY = halfLinkHeight
        line.x = sx
        line.y = sy - halfLinkHeight
        line.rotation = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun ensureRandomRadiusForPoint(pointId: String): View {
        randomRadiusViews[pointId]?.let { return it }
        val v = View(context).apply {
            alpha = 1f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // Match pointer visual tone so random radius range is clearly visible.
                setColor(Color.parseColor("#553F51B5"))
                setStroke(dp(1), Color.parseColor("#AA3F51B5"))
            }
        }
        pointerLayer.addView(
            v,
            0,
            FrameLayout.LayoutParams(dp(2), dp(2)).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        randomRadiusViews[pointId] = v
        return v
    }

    private fun removeRandomRadiusForPoint(pointId: String) {
        val v = randomRadiusViews.remove(pointId) ?: return
        pointerLayer.removeView(v)
    }

    private fun refreshRandomRadiusViews() {
        val tapIds = lastPoints.asSequence()
            .filter { it.actionType != ACTION_TYPE_DRAG }
            .map { it.id }
            .toHashSet()

        for (id in randomRadiusViews.keys.toList()) {
            if (!tapIds.contains(id) || randomTouchRadiusDp <= 0) {
                removeRandomRadiusForPoint(id)
            }
        }

        if (randomTouchRadiusDp <= 0) return

        for (point in lastPoints) {
            if (point.actionType == ACTION_TYPE_DRAG) {
                removeRandomRadiusForPoint(point.id)
            } else {
                updateRandomRadiusForPoint(point.id)
            }
        }
    }

    private fun updateRandomRadiusForPoint(pointId: String) {
        if (randomTouchRadiusDp <= 0) {
            removeRandomRadiusForPoint(pointId)
            return
        }

        val point = lastPoints.firstOrNull { it.id == pointId } ?: run {
            removeRandomRadiusForPoint(pointId)
            return
        }
        if (point.actionType == ACTION_TYPE_DRAG) {
            removeRandomRadiusForPoint(pointId)
            return
        }

        val start = views[pointId] ?: run {
            removeRandomRadiusForPoint(pointId)
            return
        }
        val radiusPx = (randomTouchRadiusDp + randomRadiusVisualExtraDp) * density
        val diameter = max(2f, radiusPx * 2f).roundToInt()
        val ring = ensureRandomRadiusForPoint(pointId)

        val lp = (ring.layoutParams as FrameLayout.LayoutParams).apply {
            width = diameter
            height = diameter
            gravity = Gravity.TOP or Gravity.START
        }
        ring.layoutParams = lp
        ring.x = start.getCenterX() - (diameter / 2f)
        ring.y = start.getCenterY() - (diameter / 2f)
    }

    private fun selectedPointerView(): DraggablePointerView? {
        val id = selectedId ?: return null
        return if (selectedEndpoint == Endpoint.START) views[id] else dragEndViews[id]
    }

    private fun selectPointer(id: String, endpoint: Endpoint) {
        if (selectedId == id && selectedEndpoint == endpoint) {
            updateMoveStickPosition(forceShow = true)
            return
        }

        selectedPointerView()?.let { prev ->
            prev.animate().cancel()
            prev.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
        }

        selectedId = id
        selectedEndpoint = endpoint

        selectedPointerView()?.let { pv ->
            pv.bringToFront()
            pv.animate().cancel()
            pv.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90L).start()

            // 선택 시 1회 배치 결정
            val ph = pointerLayer.height
            stickPlaceBelow = if (ph > 0) {
                pv.getCenterY() < (ph / 2f)
            } else true
        }

        updateMoveStickPosition(forceShow = true)
    }

    private fun clearSelection() {
        selectedPointerView()?.let { prev ->
            prev.animate().cancel()
            prev.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
        }
        selectedId = null
        selectedEndpoint = Endpoint.START
        stickPlaceBelow = true
        hideMoveStick()
    }

    private fun hideMoveStick() {
        moveStickHandle.animate().cancel()
        moveStickLine.animate().cancel()
        deletePointerBtn.animate().cancel()

        if (moveStickHandle.visibility == View.VISIBLE) {
            moveStickHandle.animate()
                .alpha(0f)
                .setDuration(90L)
                .withEndAction { moveStickHandle.visibility = View.GONE }
                .start()
        } else {
            moveStickHandle.visibility = View.GONE
            moveStickHandle.alpha = 0f
        }

        if (moveStickLine.visibility == View.VISIBLE) {
            moveStickLine.animate()
                .alpha(0f)
                .setDuration(90L)
                .withEndAction { moveStickLine.visibility = View.GONE }
                .start()
        } else {
            moveStickLine.visibility = View.GONE
            moveStickLine.alpha = 0f
        }

        if (deletePointerBtn.visibility == View.VISIBLE) {
            deletePointerBtn.animate()
                .alpha(0f)
                .setDuration(90L)
                .withEndAction { deletePointerBtn.visibility = View.GONE }
                .start()
        } else {
            deletePointerBtn.visibility = View.GONE
            deletePointerBtn.alpha = 0f
        }
    }

    // ---- Move Stick Drag ----

    private fun setupMoveStickHandleDrag() {
        moveStickHandle.setOnTouchListener { _, ev ->
            val id = selectedId ?: return@setOnTouchListener true
            val endpoint = selectedEndpoint
            val pv = selectedPointerView() ?: return@setOnTouchListener true

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleDragging = false
                    handleDownRawX = ev.rawX
                    handleDownRawY = ev.rawY
                    handleDownCenterX = pv.getCenterX()
                    handleDownCenterY = pv.getCenterY()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - handleDownRawX
                    val dy = ev.rawY - handleDownRawY
                    if (!handleDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        handleDragging = true
                        onDragStartInternal?.invoke(id, endpoint)
                    }
                    val newCx = handleDownCenterX + dx
                    val newCy = handleDownCenterY + dy
                    moveSelectedPointerToLocalCenter(id, endpoint, newCx, newCy, notifyMove = handleDragging)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (handleDragging) {
                        val current = selectedPointerView()
                        val cx = current?.getCenterX() ?: pv.getCenterX()
                        val cy = current?.getCenterY() ?: pv.getCenterY()
                        onDragEndInternal?.invoke(id, endpoint, cx, cy)
                    }
                    handleDragging = false

                    // 드래그 종료 시점에도 배치 재결정 + 재배치
                    val ph = pointerLayer.height
                    stickPlaceBelow = if (ph > 0) {
                        pv.getCenterY() < (ph / 2f)
                    } else true
                    updateMoveStickPosition(forceShow = true)

                    true
                }

                else -> true
            }
        }
    }

    private fun moveSelectedPointerToLocalCenter(
        id: String,
        endpoint: Endpoint,
        cx: Float,
        cy: Float,
        notifyMove: Boolean
    ) {
        val pv = if (endpoint == Endpoint.START) views[id] else dragEndViews[id]
        if (pv == null) return
        val parentW = pointerLayer.width.coerceAtLeast(1)
        val parentH = pointerLayer.height.coerceAtLeast(1)

        val half = pointerTouchSizePx / 2f
        val clampedCx = cx.coerceIn(half, (parentW - half).coerceAtLeast(half))
        val clampedCy = cy.coerceIn(half, (parentH - half).coerceAtLeast(half))

        pv.setCenter(clampedCx, clampedCy)
        updateDragLinkForPoint(id)
        if (endpoint == Endpoint.START) {
            updateRandomRadiusForPoint(id)
        }

        updateMoveStickPosition()

        if (notifyMove) {
            onDragMoveInternal?.invoke(id, endpoint, clampedCx, clampedCy)
        }
    }

    fun setOnReservationStartClick(block: (runMin: Int, restMin: Int, repeatCount: Int) -> Unit) {
        reservationPanel.setOnStartClick(block)
    }

    fun setOnRequestIme(block: (Boolean) -> Unit) {
        onRequestIme = block
        reservationPanel.setOnRequestIme(block) // ✅ 예약 패널도 동일하게 IME 포커스 제어
    }

    private fun updateMoveStickPosition(forceShow: Boolean = false) {
        selectedId ?: run {
            hideMoveStick()
            return
        }
        val pv = selectedPointerView() ?: run {
            clearSelection()
            return
        }
        if (pointerLayer.width <= 0 || pointerLayer.height <= 0) return

        val parentW = pointerLayer.width
        val parentH = pointerLayer.height

        val cx = pv.getCenterX()

        val btnLeft = (cx - moveStickHandleSizePx / 2f).coerceIn(
            moveStickMarginPx.toFloat(),
            (parentW - moveStickHandleSizePx - moveStickMarginPx).toFloat()
                .coerceAtLeast(moveStickMarginPx.toFloat())
        )

        val lineLeft = (cx - moveStickLineWidthPx / 2f).coerceIn(
            moveStickMarginPx.toFloat(),
            (parentW - moveStickLineWidthPx - moveStickMarginPx).toFloat()
                .coerceAtLeast(moveStickMarginPx.toFloat())
        )

        val minLineH = dp(12).toFloat()
        var handleTop: Float

        if (stickPlaceBelow) {
            val pointerBottom = pv.y + pointerTouchSizePx
            val lineTop = pointerBottom + moveStickMarginPx

            val maxHandleTop = (parentH - moveStickHandleSizePx - moveStickMarginPx).toFloat()
            val desiredHandleTop = lineTop + moveStickDesiredLenPx
            handleTop = min(desiredHandleTop, maxHandleTop)

            if (handleTop < lineTop + minLineH) {
                handleTop = (lineTop + minLineH).coerceAtMost(maxHandleTop)
            }

            val lineH = (handleTop - lineTop).coerceAtLeast(minLineH)

            val lpLine = (moveStickLine.layoutParams as FrameLayout.LayoutParams).apply {
                width = moveStickLineWidthPx
                height = lineH.roundToInt()
                gravity = Gravity.TOP or Gravity.START
            }
            moveStickLine.layoutParams = lpLine
            moveStickLine.x = lineLeft
            moveStickLine.y = lineTop
        } else {
            val pointerTop = pv.y
            val lineBottom = pointerTop - moveStickMarginPx

            val minHandleTop = moveStickMarginPx.toFloat()
            val desiredHandleTop = lineBottom - moveStickDesiredLenPx - moveStickHandleSizePx
            handleTop = desiredHandleTop.coerceAtLeast(minHandleTop)

            val lineTop = handleTop + moveStickHandleSizePx
            var lineH = (lineBottom - lineTop).coerceAtLeast(minLineH)

            val needBottom = lineTop + minLineH
            if (lineBottom < needBottom) {
                val shiftDown = (needBottom - lineBottom)
                handleTop = (handleTop + shiftDown).coerceAtMost(
                    (parentH - moveStickHandleSizePx - moveStickMarginPx).toFloat()
                        .coerceAtLeast(minHandleTop)
                )
                val newLineTop = handleTop + moveStickHandleSizePx
                lineH = (lineBottom - newLineTop).coerceAtLeast(minLineH)
            }

            val finalLineTop = handleTop + moveStickHandleSizePx
            val finalLineH = (lineBottom - finalLineTop).coerceAtLeast(minLineH)

            val lpLine = (moveStickLine.layoutParams as FrameLayout.LayoutParams).apply {
                width = moveStickLineWidthPx
                height = finalLineH.roundToInt()
                gravity = Gravity.TOP or Gravity.START
            }
            moveStickLine.layoutParams = lpLine
            moveStickLine.x = lineLeft
            moveStickLine.y = finalLineTop
        }

        val lpBtn = (moveStickHandle.layoutParams as FrameLayout.LayoutParams).apply {
            width = moveStickHandleSizePx
            height = moveStickHandleSizePx
            gravity = Gravity.TOP or Gravity.START
        }
        moveStickHandle.layoutParams = lpBtn
        moveStickHandle.x = btnLeft
        moveStickHandle.y = handleTop

        // 삭제 버튼: 이동 핸들 옆(우선 오른쪽, 공간 없으면 왼쪽)
        val desiredDelXRight = btnLeft + moveStickHandleSizePx + moveStickMarginPx
        val desiredDelXLeft = btnLeft - moveStickHandleSizePx - moveStickMarginPx
        val delX = if (desiredDelXRight + moveStickHandleSizePx <= parentW - moveStickMarginPx) {
            desiredDelXRight
        } else {
            desiredDelXLeft.coerceAtLeast(moveStickMarginPx.toFloat())
        }

        val lpDel = (deletePointerBtn.layoutParams as FrameLayout.LayoutParams).apply {
            width = moveStickHandleSizePx
            height = moveStickHandleSizePx
            gravity = Gravity.TOP or Gravity.START
        }
        deletePointerBtn.layoutParams = lpDel
        deletePointerBtn.x = delX
        deletePointerBtn.y = handleTop

        moveStickLine.bringToFront()
        moveStickHandle.bringToFront()
        deletePointerBtn.bringToFront()

        if (forceShow) {
            if (moveStickLine.visibility != View.VISIBLE) {
                moveStickLine.visibility = View.VISIBLE
                moveStickLine.alpha = 0f
                moveStickLine.animate().alpha(1f).setDuration(110L).start()
            } else if (moveStickLine.alpha < 1f) {
                moveStickLine.animate().alpha(1f).setDuration(80L).start()
            }

            if (moveStickHandle.visibility != View.VISIBLE) {
                moveStickHandle.visibility = View.VISIBLE
                moveStickHandle.alpha = 0f
                moveStickHandle.scaleX = 0.92f
                moveStickHandle.scaleY = 0.92f
                moveStickHandle.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(110L)
                    .start()
            } else if (moveStickHandle.alpha < 1f) {
                moveStickHandle.animate().alpha(1f).setDuration(80L).start()
            }

            if (deletePointerBtn.visibility != View.VISIBLE) {
                deletePointerBtn.visibility = View.VISIBLE
                deletePointerBtn.alpha = 0f
                deletePointerBtn.scaleX = 0.92f
                deletePointerBtn.scaleY = 0.92f
                deletePointerBtn.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(110L)
                    .start()
            } else if (deletePointerBtn.alpha < 1f) {
                deletePointerBtn.animate().alpha(1f).setDuration(80L).start()
            }
        } else {
            if (moveStickLine.visibility != View.VISIBLE ||
                moveStickHandle.visibility != View.VISIBLE ||
                deletePointerBtn.visibility != View.VISIBLE
            ) {
                updateMoveStickPosition(forceShow = true)
            }
        }
    }

    private fun findPointerTargetAtRaw(rawX: Float, rawY: Float): Pair<String, Endpoint>? {
        for (i in pointerLayer.childCount - 1 downTo 0) {
            val v = pointerLayer.getChildAt(i) as? DraggablePointerView ?: continue
            if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) continue
            v.getLocationOnScreen(tmpLoc)
            val left = tmpLoc[0]
            val top = tmpLoc[1]
            val right = left + v.width
            val bottom = top + v.height
            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                return pointerViewToTarget[v]
            }
        }
        return null
    }

    private fun applyResponsiveLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val landscape = w > h
        val sideMax = dp(320)
        val sideWidth = min(sideMax, (w * 0.42f).roundToInt()).coerceAtLeast(dp(240))

        val panelLp = FrameLayout.LayoutParams(
            if (landscape) sideWidth else LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (landscape) (Gravity.TOP or Gravity.START) else (Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            leftMargin = dp(12)
            rightMargin = dp(12)
            topMargin = dp(12)
        }
        controls.controlPanel.layoutParams = panelLp

        val miniLp = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(12)
            topMargin = dp(12)
        }
        miniPanelToggleBtn.layoutParams = miniLp

        controls.subtitleText.visibility = if (landscape) View.GONE else View.VISIBLE
        controls.hintText.visibility = if (landscape) View.GONE else View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()
}
