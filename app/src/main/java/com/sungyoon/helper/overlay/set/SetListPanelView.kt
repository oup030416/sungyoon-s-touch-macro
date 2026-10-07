package com.sungyoon.helper.overlay.set

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.sungyoon.helper.R
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.model.SetItemType
import com.sungyoon.helper.overlay.pointer.PointerOverlayDrawables
import com.sungyoon.helper.service.set.SetRunState

class SetListPanelView(
    context: Context,
    onBack: () -> Unit,
    private val onSelect: (String) -> Unit,
    private val onToggleMenu: (String) -> Unit,
    private val onAdd: () -> Unit,
    private val onDelete: (String) -> Unit,
    private val onDuplicate: (String) -> Unit,
    private val onStartOrResume: () -> Unit,
    private val onCancel: () -> Unit,
    private val onMove: (String, Int) -> Unit
) : SetPanelView(context, context.getString(R.string.set_title), onBack) {
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(Color.parseColor("#66D58A"))
        progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
    }
    private val progressPercent = context.setText(context.getString(R.string.set_progress_percent, 0), 12.5f).apply {
        gravity = Gravity.END
        setTextColor(Color.parseColor("#D7D7D7"))
    }
    private val elapsedTime = context.setText(context.getString(R.string.set_elapsed_time, 0L, 0L), 12.5f).apply {
        gravity = Gravity.END
        setTextColor(Color.parseColor("#D7D7D7"))
    }
    private val rowsContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private val rowViews = linkedMapOf<String, LinearLayout>()
    private val badges = linkedMapOf<String, TextView>()
    private val decorations = linkedMapOf<String, Pair<Boolean, Boolean>>()
    private data class InlineMenu(
        val itemId: String?,
        val options: List<Pair<String, () -> Unit>>,
        val onClose: () -> Unit
    )
    private var inlineMenu: InlineMenu? = null
    private var inlineMenuView: View? = null
    private val addControls = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val addButton = context.setAction(context.getString(R.string.set_add_item), Color.parseColor("#4A4A4A"), onAdd)
    private var entries = emptyList<SetItem>()
    private var selectedId: String? = null
    private var runtime = SetRunState()
    private var pendingRevealItemId: String? = null
    private val revealCurrentItem = ViewTreeObserver.OnPreDrawListener {
        val id = pendingRevealItemId ?: return@OnPreDrawListener true
        val row = rowViews[id]
        if (row == null) {
            pendingRevealItemId = null
            return@OnPreDrawListener true
        }
        val scroll = activeScrollView()
        if (isLayoutRequested || scroll.isLayoutRequested || scroll.height <= 0) return@OnPreDrawListener false
        val content = scroll.getChildAt(0) as? ViewGroup ?: return@OnPreDrawListener true
        val bounds = Rect(0, 0, row.width, row.height)
        content.offsetDescendantRectToMyCoords(row, bounds)
        val target = (bounds.top - scroll.paddingTop).coerceAtLeast(0)
        // Reserve trailing space so even the last item can sit at the viewport's top.
        val requiredHeight = target + scroll.height - scroll.paddingTop - scroll.paddingBottom
        if (content.minimumHeight < requiredHeight) {
            content.minimumHeight = requiredHeight
            return@OnPreDrawListener false
        }
        scroll.scrollTo(0, target)
        pendingRevealItemId = null
        true
    }
    private var pendingEntries: List<SetItem>? = null
    private val cancelButton = context.setAction(context.getString(R.string.set_cancel), onClick = onCancel)
    private val startButton = context.setAction(context.getString(R.string.set_start), Color.parseColor("#2E7D32"), onStartOrResume)
    private var dragId: String? = null
    private var dragOrigin = 0
    private var dragTarget = 0
    private var dragDownRawY = 0f
    private var dragRawY = 0f
    private var dragDownScroll = 0
    private var dragCenter = 0f
    private val dragFrame = object : Runnable {
        override fun run() {
            if (dragId == null) return
            val scroll = activeScrollView()
            val location = IntArray(2)
            scroll.getLocationOnScreen(location)
            val edge = context.setDp(44)
            val movement = when {
                dragRawY < location[1] + edge -> -context.setDp(7)
                dragRawY > location[1] + scroll.height - edge -> context.setDp(7)
                else -> 0
            }
            if (movement != 0) {
                scroll.scrollBy(0, movement)
                updateDragPreview()
            }
            postOnAnimation(this)
        }
    }

    init {
        body.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(context.setText(context.getString(R.string.set_progress_title), 12.5f, true),
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(elapsedTime, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.setDp(8)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progressBar, LayoutParams(0, context.setDp(16), 1f))
            addView(progressPercent, LayoutParams(context.setDp(48), LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.setDp(8)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.setDp(4)
            bottomMargin = context.setDp(10)
        })
        body.addView(rowsContainer)
        body.addView(addControls,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(12) })
        footer.addView(cancelButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = context.setDp(10)
        })
        footer.addView(startButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        setItems(emptyList(), null)
    }

    fun setItems(items: List<SetItem>, selectedItemId: String?) {
        selectedId = selectedItemId
        if (dragId != null) {
            pendingEntries = items
            return
        }
        val scroll = bodyScrollPosition()
        entries = items
        rowsContainer.removeAllViews()
        rowViews.clear()
        badges.clear()
        decorations.clear()
        inlineMenuView = null
        if (items.isEmpty()) {
            rowsContainer.addView(context.setText(context.getString(R.string.set_list_empty), 13.5f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#B8B8B8"))
                setPadding(context.setDp(8), context.setDp(32), context.setDp(8), context.setDp(24))
            })
        } else {
            items.forEach { item ->
                rowsContainer.addView(buildRow(item), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.setDp(8)
                })
            }
        }
        addControls.removeAllViews()
        val addMenu = inlineMenu?.takeIf { it.itemId == null }
        if (addMenu == null) {
            addControls.addView(addButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            addMenu.options.forEach { (label, action) ->
                addControls.addView(context.setAction(label, Color.parseColor("#5B5CE6"), action),
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = context.setDp(6) })
            }
            addControls.addView(context.setAction(context.getString(R.string.set_add_close), Color.parseColor("#3A3A3A"), addMenu.onClose).apply {
                contentDescription = context.getString(R.string.set_add_close_description)
                setPadding(context.setDp(4), context.setDp(10), context.setDp(4), context.setDp(10))
            }, LayoutParams(context.setDp(40), LayoutParams.WRAP_CONTENT))
        }
        syncDecorations()
        restoreBodyScroll(scroll)
    }

    fun setSelectedItem(id: String?) {
        selectedId = id
        syncDecorations()
    }

    fun showInlineMenu(itemId: String?, options: List<Pair<String, () -> Unit>>, onClose: () -> Unit) {
        inlineMenu = InlineMenu(itemId, options, onClose)
        setItems(entries, selectedId)
        inlineMenuView?.let { menu ->
            menu.post {
                if (menu.isAttachedToWindow) {
                    // Keep the expanded actions visible without scrolling past their parent card.
                    menu.requestRectangleOnScreen(Rect(0, 0, menu.width, minOf(menu.height, context.setDp(84))), true)
                }
            }
        }
    }

    fun clearInlineMenu() { inlineMenu = null }

    fun renderRuntime(state: SetRunState) {
        runtime = state
        if (!state.active) {
            pendingRevealItemId = null
            clearScrollContentMinimumHeights()
        }
        val percentage = SetProgress.percent(state)
        val seconds = (if (state.active) state.elapsedMs else 0L).coerceAtLeast(0L) / 1000L
        elapsedTime.text = context.getString(R.string.set_elapsed_time, seconds / 60L, seconds % 60L)
        progressBar.progress = percentage
        progressPercent.text = context.getString(R.string.set_progress_percent, percentage)
        syncDecorations()
    }

    fun savedScrollPosition(): Int = bodyScrollPosition()
    fun restoreSavedScrollPosition(scroll: Int) = restoreBodyScroll(scroll)

    fun scrollCurrentItemToTop() {
        pendingRevealItemId = runtime.currentItem?.id?.takeIf { runtime.active }
        if (pendingRevealItemId != null) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(revealCurrentItem)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(revealCurrentItem)
        pendingRevealItemId = null
        clearScrollContentMinimumHeights()
        finishDrag(commit = false)
        super.onDetachedFromWindow()
    }

    private fun buildRow(item: SetItem): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        val expanded = inlineMenu?.itemId == item.id
        val verticalPadding = context.setDp(if (expanded) 12 else 8)
        setPadding(context.setDp(14), verticalPadding, context.setDp(14), verticalPadding)
        isActivated = expanded
        contentDescription = context.getString(
            if (expanded) R.string.set_edit_close_description else R.string.set_edit_description, item.name)
        setOnClickListener { onToggleMenu(item.id) }
        val nameLine = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameLine.addView(context.setText(item.name, 17f, true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        nameLine.addView(context.setText("≡", 26f, true).apply {
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.set_reorder_description, item.name)
            isClickable = true
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> beginDrag(item.id, event.rawY)
                    MotionEvent.ACTION_MOVE -> {
                        dragRawY = event.rawY
                        updateDragPreview()
                    }
                    MotionEvent.ACTION_UP -> {
                        view.performClick()
                        finishDrag(commit = true)
                    }
                    MotionEvent.ACTION_CANCEL -> finishDrag(commit = false)
                }
                true
            }
        }, 0, LayoutParams(context.setDp(40), context.setDp(44)).apply { rightMargin = context.setDp(6) })
        addView(nameLine)
        val badge = context.setText("", 13f, true).apply {
            setTextColor(Color.parseColor("#80D8A0"))
            setPadding(0, context.setDp(4), 0, 0)
            visibility = View.GONE
        }
        addView(badge)
        addView(context.setText(itemSummary(context, item), 13.5f).apply {
            setTextColor(Color.parseColor("#D7D7D7"))
            setPadding(0, context.setDp(4), 0, 0)
        })
        inlineMenu?.takeIf { it.itemId == item.id }?.let { menu -> addView(buildInlineMenu(item, menu)) }
        rowViews[item.id] = this
        badges[item.id] = badge
    }

    private fun buildInlineMenu(item: SetItem, menu: InlineMenu): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, context.setDp(12), 0, 0)
        fun actionRow(options: List<Pair<String, () -> Unit>>, includeItemActions: Boolean): LinearLayout =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                isBaselineAligned = false
                options.forEachIndexed { index, (label, action) ->
                    addView(context.setAction(label, Color.parseColor("#3A3A3A"), action).apply {
                        textSize = 12.5f
                        setPadding(context.setDp(4), context.setDp(10), context.setDp(4), context.setDp(10))
                    }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                        if (includeItemActions || index < options.lastIndex) rightMargin = context.setDp(6)
                    })
                }
                if (includeItemActions) {
                    addView(itemActionIcon(item, R.drawable.ic_set_duplicate, R.string.set_duplicate_description,
                        Color.parseColor("#5B5CE6")) { onDuplicate(item.id) },
                        LayoutParams(context.setDp(40), LayoutParams.MATCH_PARENT).apply { rightMargin = context.setDp(6) })
                    addView(itemActionIcon(item, R.drawable.ic_set_delete, R.string.set_delete_description,
                        Color.parseColor("#8E2430")) { onDelete(item.id) },
                        LayoutParams(context.setDp(40), LayoutParams.MATCH_PARENT))
                }
            }
        if (item.type == SetItemType.RESERVED) {
            addView(actionRow(menu.options.take(2), includeItemActions = false),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(actionRow(menu.options.drop(2), includeItemActions = true),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(6) })
        } else {
            addView(actionRow(menu.options, includeItemActions = true),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        inlineMenuView = this
    }

    private fun itemActionIcon(item: SetItem, drawable: Int, description: Int, color: Int, action: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(drawable)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = PointerOverlayDrawables.roundedRippleBg(color, Color.parseColor("#40FFFFFF"), context::setDp, 14)
            contentDescription = context.getString(description, item.name)
            minimumHeight = context.setDp(46)
            setPadding(context.setDp(8), context.setDp(10), context.setDp(8), context.setDp(10))
            setOnClickListener { action() }
        }

    private fun syncDecorations() {
        rowViews.forEach { (id, row) ->
            val expanded = inlineMenu?.itemId == id
            val active = runtime.active && runtime.currentItem?.id == id
            val decoration = expanded to active
            // Progress ticks do not need to recreate every card background.
            if (decorations[id] != decoration) {
                decorations[id] = decoration
                row.isSelected = expanded
                row.isActivated = expanded
                row.background = GradientDrawable().apply {
                    cornerRadius = context.setDp(16).toFloat()
                    setColor(Color.parseColor(when {
                        active -> "#153B26"
                        expanded -> "#332E7DFF"
                        else -> "#1B1B1B"
                    }))
                    setStroke(context.setDp(if (expanded || active) 2 else 1), Color.parseColor(when {
                        active -> "#66D58A"
                        expanded -> "#7E8BFF"
                        else -> "#2C2C2C"
                    }))
                }
            }
            badges[id]?.apply {
                visibility = if (active) VISIBLE else GONE
                text = context.getString(R.string.set_active_item)
            }
        }
        cancelButton.isEnabled = runtime.active && !runtime.stopping
        cancelButton.alpha = if (cancelButton.isEnabled) 1f else 0.45f
        startButton.text = context.getString(if (runtime.active) R.string.set_resume else R.string.set_start)
        startButton.isEnabled = !runtime.stopping
        startButton.alpha = if (startButton.isEnabled) 1f else 0.45f
    }

    private fun beginDrag(id: String, rawY: Float) {
        val row = rowViews[id] ?: return
        selectedId = id
        onSelect(id)
        syncDecorations()
        dragId = id
        dragOrigin = entries.indexOfFirst { it.id == id }
        dragTarget = dragOrigin
        dragDownRawY = rawY
        dragRawY = rawY
        dragDownScroll = activeScrollView().scrollY
        dragCenter = row.top + row.height / 2f
        row.elevation = context.setDp(12).toFloat()
        activeScrollView().requestDisallowInterceptTouchEvent(true)
        postOnAnimation(dragFrame)
    }

    private fun updateDragPreview() {
        val id = dragId ?: return
        val source = rowViews[id] ?: return
        val delta = dragRawY - dragDownRawY + activeScrollView().scrollY - dragDownScroll
        val center = dragCenter + delta
        var target = dragOrigin
        while (target < entries.lastIndex) {
            val next = rowViews[entries[target + 1].id] ?: break
            if (center <= next.top + next.height / 2f) break
            target++
        }
        while (target > 0) {
            val previous = rowViews[entries[target - 1].id] ?: break
            if (center >= previous.top + previous.height / 2f) break
            target--
        }
        dragTarget = target
        val shift = source.height + context.setDp(8)
        entries.forEachIndexed { index, item ->
            rowViews[item.id]?.translationY = when {
                item.id == id -> delta
                target > dragOrigin && index in (dragOrigin + 1)..target -> -shift.toFloat()
                target < dragOrigin && index in target until dragOrigin -> shift.toFloat()
                else -> 0f
            }
        }
    }

    private fun finishDrag(commit: Boolean) {
        val id = dragId ?: return
        removeCallbacks(dragFrame)
        dragId = null
        activeScrollView().requestDisallowInterceptTouchEvent(false)
        rowViews.values.forEach { it.translationY = 0f; it.elevation = 0f }
        if (commit && dragTarget != dragOrigin) onMove(id, dragTarget)
        pendingEntries?.let {
            pendingEntries = null
            setItems(it, selectedId)
        }
    }

    companion object {
        internal fun typeLabel(context: Context, type: SetItemType): String = context.getString(when (type) {
            SetItemType.TOUCH -> R.string.set_type_touch
            SetItemType.RESERVED -> R.string.set_type_reserved
            SetItemType.WAIT -> R.string.set_type_wait
        })

        internal fun itemSummary(context: Context, item: SetItem): String {
            if (item is SetItem.Wait) return context.getString(R.string.set_wait_summary, SetProgressFormatter.seconds(context, item.durationMs))
            val drags = item.points.count { it.actionType == HighlightingPoint.ACTION_TYPE_DRAG }
            val points = context.getString(R.string.preset_count, item.points.size - drags, drags)
            return if (item is SetItem.Reserved) points + "\n" + context.getString(R.string.set_reservation_summary,
                item.reservation.runSeconds, item.reservation.restSeconds, item.reservation.repeatCount) else points
        }
    }
}
