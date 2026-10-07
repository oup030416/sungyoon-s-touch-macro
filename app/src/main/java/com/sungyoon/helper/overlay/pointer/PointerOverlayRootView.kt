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
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import com.sungyoon.helper.ui.DirectScrollView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.sungyoon.helper.R
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_DRAG
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.overlay.OverlayImeController
import com.sungyoon.helper.overlay.set.SetPanelSurface
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
    private var keyboardInsetBottom = 0
    private var controlInsets = Insets.NONE
    private var onRequestIme: ((Boolean) -> Unit)? = null
    private val keyboard = OverlayImeController(this, ::requestIme)

    private var onPointerSizeChanged: ((Int) -> Unit)? = null
    private var suppressPointerSizeListener = false
    private var onRandomTouchRadiusChanged: ((Int) -> Unit)? = null
    private var suppressRandomRadiusListener = false

    private var onDeletePointClick: ((String) -> Unit)? = null

    // ✅ 예약 버튼 콜백
    private var onReserveClick: (() -> Unit)? = null
    private var onPresetListClick: (() -> Unit)? = null
    private var onSetClick: (() -> Unit)? = null
    private var presetBack: (() -> Unit)? = null
    private var otherExecutionBlocked = false
    private val setContentHost = FrameLayout(context).apply { visibility = View.GONE }
    private var setContentMinimized = false

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

    private val controlPanelScrollHost = DirectScrollView(context).apply {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        addView(
            controls.controlPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

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
            setOnCloseClick { closePresetPanelAndReturn() }
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
        addView(controlPanelScrollHost)

        // ✅ controlPanel 위에 예약 패널을 올려 "완전히 가리기"
        addView(reservationPanel)
        addView(presetPanel)
        addView(setContentHost)

        addView(miniPanelToggleBtn)
        addView(modalHost)
        miniPanelToggleBtn.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!panelVisible || setContentMinimized) updateMoveStickPosition()
        }

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
        miniPanelToggleBtn.setOnClickListener {
            if (setContentMinimized) restoreSetContent() else setControlPanelVisible(true)
        }

        // ✅ 예약 버튼 클릭 → 컨트롤러에서 등록한 콜백 호출(기본 동작: 예약 패널 열기)
        controls.reserveBtn.setOnClickListener { onReserveClick?.invoke() }
        controls.presetListBtn.setOnClickListener { onPresetListClick?.invoke() }
        controls.setBtn.setOnClickListener { onSetClick?.invoke() }

        // controlPanel 레이아웃이 바뀌면(회전/리사이즈) 예약 패널도 즉시 동기화
        controls.controlPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateControlPanelViewport()
            if (reservationPanel.visibility == View.VISIBLE) {
                syncReservationPanelLayout()
            }
            if (presetPanel.visibility == View.VISIBLE) {
                syncPresetPanelLayout()
            }
            syncSetContentLayout()
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
            }
            if (controls.dragDurationEdit.hasFocus() &&
                !isTouchInsideViewRaw(ev.rawX, ev.rawY, controls.dragDurationEdit)
            ) {
                controls.dragDurationEdit.clearFocus()
                hideKeyboard()
            }

            val touchedPointerTarget = findPointerTargetAtRaw(ev.rawX, ev.rawY)
            val touchedHandle = isTouchInsideViewRaw(ev.rawX, ev.rawY, moveStickHandle)
            val touchedDelete = isTouchInsideViewRaw(ev.rawX, ev.rawY, deletePointerBtn)

            // ✅ 예약 패널이 떠있을 땐 예약 패널도 "패널 영역"으로 간주
            val touchedPanel =
                isTouchInsideViewRaw(ev.rawX, ev.rawY, controlPanelScrollHost) ||
                        (reservationPanel.visibility == View.VISIBLE && isTouchInsideViewRaw(ev.rawX, ev.rawY, reservationPanel)) ||
                        (presetPanel.visibility == View.VISIBLE && isTouchInsideViewRaw(ev.rawX, ev.rawY, presetPanel)) ||
                        (setContentHost.visibility == View.VISIBLE && isTouchInsideViewRaw(ev.rawX, ev.rawY, setContentHost)) ||
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

    fun setOnSetClick(block: () -> Unit) { onSetClick = block }

    fun setOtherExecutionBlocked(blocked: Boolean) {
        otherExecutionBlocked = blocked
        // Keep the guarded click reachable so a blocked action can explain its owner.
        controls.playToggleBtn.isEnabled = true
        controls.playToggleBtn.alpha = if (blocked) 0.45f else 1f
        controls.playToggleBtn.contentDescription = context.getString(
            if (blocked) R.string.set_blocked_message
            else if (controls.playToggleBtn.text == "■") R.string.pointer_play_desc_stop
            else R.string.pointer_play_desc_start
        )
        controls.hintText.text = context.getString(
            if (blocked) R.string.set_blocked_message else R.string.pointer_control_hint
        )
        reservationPanel.setExecutionBlocked(blocked)
    }

    fun showSetContent(view: View?, showPointers: Boolean = false) {
        clearSelection()
        closeReservationPanel()
        closePresetPanel()
        setContentMinimized = false
        miniPanelToggleBtn.animate().cancel()
        setContentHost.removeAllViews()
        if (view == null) {
            setContentHost.visibility = View.GONE
            controlPanelScrollHost.visibility = if (panelVisible) View.VISIBLE else View.GONE
            miniPanelToggleBtn.visibility = if (panelVisible) View.GONE else View.VISIBLE
            miniPanelToggleBtn.alpha = if (panelVisible) 0f else 1f
            pointerLayer.visibility = View.VISIBLE
        } else {
            if (!panelVisible) setControlPanelVisible(true)
            controlPanelScrollHost.visibility = View.GONE
            miniPanelToggleBtn.visibility = View.GONE
            setContentHost.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            setContentHost.visibility = View.VISIBLE
            pointerLayer.visibility = if (showPointers) View.VISIBLE else View.INVISIBLE
            setContentHost.bringToFront()
            syncSetContentLayout()
        }
    }

    fun minimizeSetContent() {
        if (setContentHost.childCount == 0 || setContentHost.visibility != View.VISIBLE) return
        // Keep the mounted editor and scoped pointer target intact while the panel is hidden.
        setContentMinimized = true
        setContentHost.visibility = View.GONE
        miniPanelToggleBtn.animate().cancel()
        miniPanelToggleBtn.visibility = View.VISIBLE
        miniPanelToggleBtn.alpha = 1f
        miniPanelToggleBtn.bringToFront()
        updateMoveStickPosition()
    }

    private fun restoreSetContent() {
        if (!setContentMinimized || setContentHost.childCount == 0) return
        setContentMinimized = false
        miniPanelToggleBtn.animate().cancel()
        miniPanelToggleBtn.visibility = View.GONE
        miniPanelToggleBtn.alpha = 0f
        controlPanelScrollHost.visibility = View.GONE
        setContentHost.visibility = View.VISIBLE
        setContentHost.bringToFront()
        syncSetContentLayout()
        updateMoveStickPosition()
    }

    private fun syncSetContentLayout() {
        val src = controlPanelScrollHost.layoutParams as? FrameLayout.LayoutParams ?: return
        val viewportHeight = resolvePanelViewportHeight()
        (setContentHost.getChildAt(0) as? SetPanelSurface)?.setMaxViewportHeight(viewportHeight)
        setContentHost.layoutParams = FrameLayout.LayoutParams(src.width, LayoutParams.WRAP_CONTENT).apply {
            gravity = src.gravity
            leftMargin = src.leftMargin
            rightMargin = src.rightMargin
            topMargin = src.topMargin
            bottomMargin = src.bottomMargin
        }
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

    fun openPresetPanel(importMode: Boolean = false, onBack: (() -> Unit)? = null) {
        if (importMode) setContentHost.visibility = View.GONE
        presetBack = onBack
        presetPanel.setImportMode(importMode)
        presetPanel.setBackText(context.getString(if (importMode) R.string.set_back else R.string.pointer_panel_close))
        setPresetPanelVisible(true)
    }

    fun closePresetPanelAndReturn() {
        closePresetPanel()
        val back = presetBack
        presetBack = null
        back?.invoke()
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

    fun setOnPresetUpdateClick(block: (String) -> Unit) {
        presetPanel.setOnUpdateClick(block)
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
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        modalHost.bringToFront()
        modalHost.showConfirmationDialog(
            title = title,
            message = message,
            confirmText = confirmText,
            cancelText = cancelText,
            destructive = destructive,
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
        modalHost.bringToFront()
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

        if (!isAttachedToWindow) {
            if (visible) {
                if (!panelVisible) setControlPanelVisible(true)
                closePresetPanel()
                syncReservationPanelLayout()
            }
            restoreVisibility(reservationPanel, visible)
            onReservationPanelVisibleChanged?.invoke(visible)
            return
        }

        if (visible) {
            // 패널이 숨김 상태면 먼저 보이게
            if (!panelVisible) setControlPanelVisible(true)

            if (!panelVisible) setControlPanelVisible(true)
            closePresetPanel()
            syncReservationPanelLayout()

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

        if (!isAttachedToWindow) {
            if (visible) {
                if (!panelVisible) setControlPanelVisible(true)
                closeReservationPanel()
                syncPresetPanelLayout()
                presetPanel.bringToFront()
            }
            restoreVisibility(presetPanel, visible)
            onPresetPanelVisibleChanged?.invoke(visible)
            return
        }

        if (visible) {
            if (!panelVisible) setControlPanelVisible(true)
            closeReservationPanel()
            syncPresetPanelLayout()

            presetPanel.visibility = View.VISIBLE
            presetPanel.bringToFront()
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


    private fun syncReservationPanelLayout() {
        syncOverlayPanelLayout(reservationPanel)
    }

    private fun syncPresetPanelLayout() {
        syncOverlayPanelLayout(presetPanel)
    }

    private fun syncOverlayPanelLayout(panel: View) {
        val src = controlPanelScrollHost.layoutParams as? FrameLayout.LayoutParams ?: return
        val maxViewportHeight = resolvePanelViewportHeight()
        if (panel is PointerOverlayReservationPanelView) {
            panel.setMaxViewportHeight(maxViewportHeight)
        } else if (panel is PointerOverlayPresetPanelView) {
            panel.setMaxViewportHeight(maxViewportHeight)
        }

        val lp = FrameLayout.LayoutParams(src.width, LayoutParams.WRAP_CONTENT).apply {
            gravity = src.gravity
            leftMargin = src.leftMargin
            topMargin = src.topMargin
            rightMargin = src.rightMargin
            bottomMargin = src.bottomMargin
        }
        panel.layoutParams = lp
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
        edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        edit.setSingleLine(true)

        edit.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                keyboard.show(edit)
            }
            false
        }

        edit.setOnClickListener { keyboard.show(edit) }

        edit.setOnFocusChangeListener { _, hasFocus ->
            keyboard.onFocusChanged(edit, hasFocus)
        }

        edit.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                hideKeyboard()
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
        keyboard.hide()
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

        if (!isAttachedToWindow) {
            if (!visible) {
                closeReservationPanel()
                closePresetPanel()
            }
            restoreVisibility(controlPanelScrollHost, visible)
            restoreVisibility(miniPanelToggleBtn, !visible)
            onControlPanelVisibleChanged?.invoke(visible)
            updateMoveStickPosition()
            return
        }

        if (visible) {
            controlPanelScrollHost.visibility = View.VISIBLE
            controlPanelScrollHost.alpha = 0f
            controlPanelScrollHost.scaleX = 0.96f
            controlPanelScrollHost.scaleY = 0.96f
            controlPanelScrollHost.animate()
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
            }
            if (controls.dragDurationEdit.hasFocus()) {
                controls.dragDurationEdit.clearFocus()
                hideKeyboard()
            }

            controlPanelScrollHost.animate().cancel()
            controlPanelScrollHost.animate()
                .alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(120L)
                .withEndAction { controlPanelScrollHost.visibility = View.GONE }
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
        updateMoveStickPosition()
    }

    /** Initial restoration must finish before the first frame, without a transition from the default panel. */
    private fun restoreVisibility(view: View, visible: Boolean) {
        view.animate().cancel()
        view.visibility = if (visible) View.VISIBLE else View.GONE
        view.alpha = if (visible) 1f else 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val compatibleInsets = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        val keyboardBottom = if (compatibleInsets.isVisible(WindowInsetsCompat.Type.ime())) {
            compatibleInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        } else 0
        val safeInsets = compatibleInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        if (keyboardInsetBottom != keyboardBottom || controlInsets != safeInsets) {
            keyboardInsetBottom = keyboardBottom
            controlInsets = safeInsets
            post {
                if (isAttachedToWindow) {
                    applyResponsiveLayout(width, height)
                    updateMoveStickPosition()
                }
            }
        }
        return super.onApplyWindowInsets(insets)
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

    fun setOnClearAllClick(block: () -> Unit) {
        controls.clearAllBtn.setOnClickListener { block() }
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

    fun setOnClearAllClick(block: () -> Unit) {
        controls.clearAllBtn.setOnClickListener { block() }
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
        if (otherExecutionBlocked) controls.playToggleBtn.contentDescription = context.getString(R.string.set_blocked_message)
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

    fun clearDisplayedPoints() {
        syncPoints(
            points = emptyList(), labelProvider = { _, _ -> "" }, draggingIds = emptySet(),
            onDragStart = { _, _ -> }, onDragMove = { _, _, _, _ -> }, onDragEnd = { _, _, _, _ -> }
        )
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
                                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                                    // UP can contain a newer position than the last delivered MOVE.
                                    moveSelectedPointerToLocalCenter(
                                        pointId, endpoint,
                                        downCenterX + ev.rawX - downRawX,
                                        downCenterY + ev.rawY - downRawY,
                                        notifyMove = true
                                    )
                                }
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
                    if (handleDragging) {
                        val newCx = handleDownCenterX + dx
                        val newCy = handleDownCenterY + dy
                        moveSelectedPointerToLocalCenter(id, endpoint, newCx, newCy, notifyMove = true)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (handleDragging) {
                        if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            moveSelectedPointerToLocalCenter(
                                id, endpoint,
                                handleDownCenterX + ev.rawX - handleDownRawX,
                                handleDownCenterY + ev.rawY - handleDownRawY,
                                notifyMove = true
                            )
                        }
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

        // The gesture center can reach every pixel; the larger hitbox may be clipped.
        var clampedCx = cx.coerceIn(0f, (parentW - 1).toFloat())
        var clampedCy = cy.coerceIn(0f, (parentH - 1).toFloat())

        val opposite = if (endpoint == Endpoint.START) dragEndViews[id] else views[id]
        if (opposite != null) {
            val anchorX = opposite.getCenterX()
            val anchorY = opposite.getCenterY()
            // Snap only during editing; loading saved points must preserve their coordinates.
            // Keep the aligned coordinate exact even if the anchor is outside the current viewport.
            if (abs(cx - anchorX) >= abs(cy - anchorY)) {
                clampedCy = anchorY
            } else {
                clampedCx = anchorX
            }
        }

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
        val usableWidth = parentW - controlInsets.left - controlInsets.right
        val usableHeight = parentH - controlInsets.top - max(keyboardInsetBottom, controlInsets.bottom)
        if (usableWidth < moveStickHandleSizePx * 3 + moveStickMarginPx * 4 ||
            usableHeight < moveStickHandleSizePx + moveStickMarginPx * 2) {
            hideMoveStickImmediately()
            return
        }
        val safeLeft = controlInsets.left + moveStickMarginPx
        val safeRight = parentW - controlInsets.right - moveStickMarginPx
        val minHandleTop = (controlInsets.top + moveStickMarginPx).toFloat()
        val maxHandleTop = (parentH - max(keyboardInsetBottom, controlInsets.bottom) -
            moveStickHandleSizePx - moveStickMarginPx).toFloat().coerceAtLeast(minHandleTop)

        val cx = pv.getCenterX()

        val btnLeft = (cx - moveStickHandleSizePx / 2f).coerceIn(
            safeLeft.toFloat(),
            (safeRight - moveStickHandleSizePx).toFloat().coerceAtLeast(safeLeft.toFloat())
        )
        val desiredDelXRight = btnLeft + moveStickHandleSizePx + moveStickMarginPx
        val desiredDelXLeft = btnLeft - moveStickHandleSizePx - moveStickMarginPx
        val delX = if (desiredDelXRight + moveStickHandleSizePx <= safeRight) {
            desiredDelXRight
        } else {
            desiredDelXLeft.coerceAtLeast(safeLeft.toFloat())
        }

        val pointerEdge = if (stickPlaceBelow) {
            pv.y + pointerTouchSizePx + moveStickMarginPx
        } else pv.y - moveStickMarginPx
        val desiredHandleTop = if (stickPlaceBelow) {
            pointerEdge + moveStickDesiredLenPx
        } else pointerEdge - moveStickDesiredLenPx - moveStickHandleSizePx
        var handleTop = desiredHandleTop.coerceIn(minHandleTop, maxHandleTop)
        if ((!panelVisible || setContentMinimized) && miniPanelToggleBtn.visibility == View.VISIBLE &&
            miniPanelToggleBtn.width > 0 && miniPanelToggleBtn.height > 0) {
            miniPanelToggleBtn.getLocationOnScreen(tmpLoc)
            val (toggleLeft, toggleTop) = screenCenterToLocal(tmpLoc[0].toFloat(), tmpLoc[1].toFloat())
            val toggleRight = toggleLeft + miniPanelToggleBtn.width
            val toggleBottom = toggleTop + miniPanelToggleBtn.height
            val overlapsHorizontally = (btnLeft < toggleRight && btnLeft + moveStickHandleSizePx > toggleLeft) ||
                (delX < toggleRight && delX + moveStickHandleSizePx > toggleLeft)
            if (overlapsHorizontally && handleTop < toggleBottom &&
                handleTop + moveStickHandleSizePx > toggleTop) {
                // The collapsed panel toggle sits above the pointer layer and must stay reachable.
                val belowToggle = toggleBottom + moveStickMarginPx
                val aboveToggle = toggleTop - moveStickMarginPx - moveStickHandleSizePx
                handleTop = when {
                    belowToggle <= maxHandleTop -> belowToggle.coerceAtLeast(minHandleTop)
                    aboveToggle >= minHandleTop -> aboveToggle.coerceAtMost(maxHandleTop)
                    else -> {
                        hideMoveStickImmediately()
                        return
                    }
                }
            }
        }
        val buttonCenterX = btnLeft + moveStickHandleSizePx / 2f
        // Clamped buttons need a diagonal connector when the point reaches a screen edge.
        if (stickPlaceBelow) {
            positionMoveStickLine(cx, pointerEdge, buttonCenterX, handleTop)
        } else {
            positionMoveStickLine(buttonCenterX, handleTop + moveStickHandleSizePx, cx, pointerEdge)
        }

        val lpBtn = (moveStickHandle.layoutParams as FrameLayout.LayoutParams).apply {
            width = moveStickHandleSizePx
            height = moveStickHandleSizePx
            gravity = Gravity.TOP or Gravity.START
        }
        moveStickHandle.layoutParams = lpBtn
        moveStickHandle.x = btnLeft
        moveStickHandle.y = handleTop

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

    private fun hideMoveStickImmediately() {
        // Cancel fades so a rapid viewport recovery cannot receive a stale GONE callback.
        listOf(moveStickLine, moveStickHandle, deletePointerBtn).forEach {
            it.animate().cancel()
            it.visibility = View.GONE
            it.alpha = 0f
        }
    }

    private fun positionMoveStickLine(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val dx = toX - fromX
        val dy = toY - fromY
        val lp = (moveStickLine.layoutParams as FrameLayout.LayoutParams).apply {
            width = moveStickLineWidthPx
            height = hypot(dx, dy).roundToInt().coerceAtLeast(1)
            gravity = Gravity.TOP or Gravity.START
        }
        moveStickLine.layoutParams = lp
        moveStickLine.pivotX = moveStickLineWidthPx / 2f
        moveStickLine.pivotY = 0f
        moveStickLine.rotation = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() - 90f
        moveStickLine.x = fromX - moveStickLineWidthPx / 2f
        moveStickLine.y = fromY
    }

    private fun findPointerTargetAtRaw(rawX: Float, rawY: Float): Pair<String, Endpoint>? {
        if (pointerLayer.visibility != View.VISIBLE) return null
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
        val safeWidth = (w - controlInsets.left - controlInsets.right).coerceAtLeast(1)
        val safeHeight = (h - controlInsets.top - controlInsets.bottom).coerceAtLeast(1)
        val landscape = safeWidth > safeHeight
        val sideMax = dp(320)
        val panelWidth = (safeWidth - dp(24)).coerceAtLeast(1)
        val sideWidth = min(sideMax, (safeWidth * 0.42f).roundToInt())
            .coerceAtLeast(dp(240)).coerceAtMost(panelWidth)

        val panelLp = FrameLayout.LayoutParams(
            if (landscape) sideWidth else LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = controlInsets.left + dp(12)
            rightMargin = controlInsets.right + dp(12)
            topMargin = controlInsets.top + dp(12)
        }
        controlPanelScrollHost.layoutParams = panelLp
        updateControlPanelViewport()
        syncReservationPanelLayout()
        syncPresetPanelLayout()
        syncSetContentLayout()
        // Only control surfaces avoid system/IME areas; the pointer plane stays unpadded.
        modalHost.setPadding(controlInsets.left, controlInsets.top, controlInsets.right,
            max(keyboardInsetBottom, controlInsets.bottom))

        val miniLp = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = controlInsets.left + dp(12)
            rightMargin = controlInsets.right + dp(12)
            topMargin = controlInsets.top + dp(12)
        }
        miniPanelToggleBtn.layoutParams = miniLp

        controls.subtitleText.visibility = if (landscape) View.GONE else View.VISIBLE
        controls.hintText.visibility = if (landscape) View.GONE else View.VISIBLE
    }

    private fun updateControlPanelViewport() {
        val hostLp = controlPanelScrollHost.layoutParams as? FrameLayout.LayoutParams ?: return
        val availableHeight = resolvePanelViewportHeight()
        if (availableHeight <= 0) return
        val widthHint = if (hostLp.width > 0) {
            hostLp.width
        } else {
            (width - hostLp.leftMargin - hostLp.rightMargin).coerceAtLeast(1)
        }
        val contentHeight = measureDesiredHeight(controls.controlPanel, widthHint)
        val compact = contentHeight > availableHeight
        val desiredHeight = if (compact) availableHeight else LayoutParams.WRAP_CONTENT
        if (hostLp.height != desiredHeight) {
            controlPanelScrollHost.layoutParams = FrameLayout.LayoutParams(hostLp.width, desiredHeight).apply {
                gravity = hostLp.gravity
                leftMargin = hostLp.leftMargin
                rightMargin = hostLp.rightMargin
                topMargin = hostLp.topMargin
                bottomMargin = hostLp.bottomMargin
            }
        }
        controlPanelScrollHost.isFillViewport = compact
    }

    private fun resolvePanelViewportHeight(): Int {
        val hostLp = controlPanelScrollHost.layoutParams as? FrameLayout.LayoutParams
        val topMargin = hostLp?.topMargin ?: dp(12)
        val bottomPadding = dp(12)
        return if (height > 0) {
            (height - max(keyboardInsetBottom, controlInsets.bottom) - topMargin - bottomPadding).coerceAtLeast(1)
        } else dp(220)
    }

    private fun measureDesiredHeight(view: View, widthPx: Int): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            widthPx.coerceAtLeast(1),
            View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()
}
