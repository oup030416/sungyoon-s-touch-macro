package com.sungyoon.helper.overlay.pointer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.ViewGroup
import com.sungyoon.helper.R
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_DRAG
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class PointerOverlayPresetPanelView(
    context: Context,
    private val dp: (Int) -> Int
) : LinearLayout(context) {

    private val dateFormat = SimpleDateFormat("yy/MM/dd", Locale.KOREA)
    private val listContainer = LinearLayout(context).apply {
        orientation = VERTICAL
    }

    private var presets: List<PresetEntry> = emptyList()
    private var selectedPresetId: String? = null

    private var onCloseClick: (() -> Unit)? = null
    private var onAddCurrentClick: (() -> Unit)? = null
    private var onDeleteClick: ((String) -> Unit)? = null
    private var onUpdateClick: ((String) -> Unit)? = null
    private var onLoadClick: ((String) -> Unit)? = null
    private var onRenameClick: ((PresetEntry) -> Unit)? = null

    private val deleteBtn: Button
    private val updateBtn: Button
    private val loadBtn: Button
    private lateinit var headerRowView: View
    private lateinit var bodyContentView: LinearLayout
    private lateinit var bodyScrollView: ScrollView
    private lateinit var footerRowView: View
    private lateinit var addCurrentBtn: Button
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
        headerRowView = buildHeader()
        bodyContentView = buildBodyContent()
        bodyScrollView = buildBodyScrollView()

        footerRowView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        deleteBtn = actionButton(
            text = context.getString(R.string.preset_delete),
            fillColor = Color.parseColor("#8E2430")
        ) {
            selectedPresetId?.let { onDeleteClick?.invoke(it) }
        }.apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        updateBtn = actionButton(
            text = context.getString(R.string.preset_update),
            fillColor = Color.parseColor("#5B5CE6")
        ) {
            selectedPresetId?.let { onUpdateClick?.invoke(it) }
        }.apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            }
        }

        loadBtn = actionButton(
            text = context.getString(R.string.preset_load),
            fillColor = Color.parseColor("#5B5CE6")
        ) {
            selectedPresetId?.let { onLoadClick?.invoke(it) }
        }.apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f).apply {
                leftMargin = dp(10)
            }
        }

        (footerRowView as LinearLayout).addView(deleteBtn)
        (footerRowView as LinearLayout).addView(updateBtn)
        (footerRowView as LinearLayout).addView(loadBtn)
        rebuildLayout(compact = false)
        syncActionButtons()
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { updateAdaptiveLayoutMode() } }
    }

    fun setOnCloseClick(block: () -> Unit) {
        onCloseClick = block
    }

    fun setOnAddCurrentClick(block: () -> Unit) {
        onAddCurrentClick = block
    }

    fun setOnDeleteClick(block: (String) -> Unit) {
        onDeleteClick = block
    }

    fun setOnUpdateClick(block: (String) -> Unit) {
        onUpdateClick = block
    }

    fun setOnLoadClick(block: (String) -> Unit) {
        onLoadClick = block
    }

    fun setOnRenameClick(block: (PresetEntry) -> Unit) {
        onRenameClick = block
    }

    fun setPresets(entries: List<PresetEntry>) {
        presets = entries.sortedByDescending { it.createdAtEpochMs }
        if (selectedPresetId != null && presets.none { it.id == selectedPresetId }) {
            selectedPresetId = null
        }
        renderList()
    }

    fun getSelectedPresetId(): String? = selectedPresetId

    fun setSelectedPresetId(presetId: String?) {
        selectedPresetId = presetId
        renderList()
    }

    fun setMaxViewportHeight(px: Int) {
        maxViewportHeightPx = px.coerceAtLeast(0)
        post { updateAdaptiveLayoutMode() }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.preset_panel_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = Button(context).apply {
            text = context.getString(R.string.pointer_panel_close)
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

        row.addView(title)
        row.addView(closeBtn)
        return row
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
            addView(listContainer)
            addCurrentBtn = actionButton(
                text = context.getString(R.string.preset_add_current),
                fillColor = Color.parseColor("#4A4A4A")
            ) {
                onAddCurrentClick?.invoke()
            }.apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
            }
            addView(addCurrentBtn)
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        if (presets.isEmpty()) {
            listContainer.addView(
                TextView(context).apply {
                    text = context.getString(R.string.preset_empty)
                    setTextColor(Color.parseColor("#B8B8B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(8), dp(32), dp(8), dp(20))
                }
            )
            syncActionButtons()
            return
        }

        presets.forEachIndexed { index, preset ->
            listContainer.addView(buildPresetItem(preset))
            if (index < presets.lastIndex) {
                listContainer.addView(
                    View(context).apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(8))
                    }
                )
            }
        }
        syncActionButtons()
    }

    private fun buildPresetItem(preset: PresetEntry): View {
        val selected = preset.id == selectedPresetId
        val dragCount = preset.points.count { it.actionType == ACTION_TYPE_DRAG }
        val tapCount = preset.points.size - dragCount
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) Color.parseColor("#332E7DFF") else Color.parseColor("#1B1B1B"))
                setStroke(dp(if (selected) 2 else 1), if (selected) Color.parseColor("#7E8BFF") else Color.parseColor("#2C2C2C"))
            }
            setOnClickListener {
                selectedPresetId = preset.id
                renderList()
            }

            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = preset.name
                            setTextColor(Color.WHITE)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                        }
                    )
                    addView(
                        ImageButton(context).apply {
                            setImageResource(android.R.drawable.ic_menu_edit)
                            background = PointerOverlayDrawables.circleRippleBg(
                                baseColor = Color.parseColor("#22FFFFFF"),
                                rippleColor = Color.parseColor("#33FFFFFF")
                            )
                            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                            layoutParams = LayoutParams(dp(38), dp(38))
                            setOnClickListener { onRenameClick?.invoke(preset) }
                        }
                    )
                }
            )
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.preset_count, tapCount, dragCount)
                    setTextColor(Color.parseColor("#D7D7D7"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setPadding(0, dp(6), 0, 0)
                }
            )
            addView(
                TextView(context).apply {
                    text = context.getString(
                        R.string.preset_date,
                        dateFormat.format(Date(preset.createdAtEpochMs))
                    )
                    setTextColor(Color.parseColor("#A8A8A8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
    }

    private fun syncActionButtons() {
        val enabled = selectedPresetId != null
        deleteBtn.isEnabled = enabled
        deleteBtn.alpha = if (enabled) 1f else 0.45f
        updateBtn.isEnabled = enabled
        updateBtn.alpha = if (enabled) 1f else 0.45f
        loadBtn.isEnabled = enabled
        loadBtn.alpha = if (enabled) 1f else 0.45f
    }

    private fun actionButton(
        text: String,
        fillColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = PointerOverlayDrawables.roundedRippleBg(
                fillColor = fillColor,
                rippleColor = Color.parseColor("#40FFFFFF"),
                dp = dp,
                radiusDp = 14
            )
            minHeight = dp(46)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }
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
}
