package com.sungyoon.helper.overlay.set

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
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
    private val onEdit: (String) -> Unit,
    private val onAdd: () -> Unit,
    private val onDelete: () -> Unit,
    private val onDuplicate: () -> Unit,
    private val onToggleRun: () -> Unit,
    private val onMove: (String, Int) -> Unit
) : SetPanelView(context, context.getString(R.string.set_title), onBack) {
    private val status = context.setText(context.getString(R.string.set_status_idle), 12.5f).apply {
        setTextColor(Color.parseColor("#B8B8B8"))
        setPadding(0, 0, 0, context.setDp(10))
    }
    private val rowsContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private val rowViews = linkedMapOf<String, LinearLayout>()
    private val badges = linkedMapOf<String, TextView>()
    private var entries = emptyList<SetItem>()
    private var selectedId: String? = null
    private var runtime = SetRunState()
    private var pendingEntries: List<SetItem>? = null
    private val deleteButton = context.setAction(context.getString(R.string.preset_delete), Color.parseColor("#8E2430"), onDelete)
    private val duplicateButton = context.setAction(context.getString(R.string.set_duplicate), onClick = onDuplicate)
    private val startButton = context.setAction(context.getString(R.string.set_start), Color.parseColor("#2E7D32"), onToggleRun)
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
        body.addView(status)
        body.addView(rowsContainer)
        body.addView(context.setAction(context.getString(R.string.set_add_item), Color.parseColor("#4A4A4A"), onAdd),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(12) })
        listOf(deleteButton, duplicateButton, startButton).forEachIndexed { index, button ->
            footer.addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, if (index == 2) 2f else 1f).apply {
                leftMargin = if (index == 0) 0 else context.setDp(8)
            })
        }
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
        syncDecorations()
        restoreBodyScroll(scroll)
    }

    fun setSelectedItem(id: String?) {
        selectedId = id
        syncDecorations()
    }

    fun renderRuntime(state: SetRunState) {
        runtime = state
        status.text = if (state.active) {
            context.getString(R.string.set_status_position, state.pass, state.itemPosition, state.itemCount) +
                "\n" + SetProgressFormatter.title(context, state) + " · " + SetProgressFormatter.detail(context, state)
        } else SetProgressFormatter.error(context, state) ?: context.getString(R.string.set_status_idle)
        syncDecorations()
    }

    fun savedScrollPosition(): Int = bodyScrollPosition()
    fun restoreSavedScrollPosition(scroll: Int) = restoreBodyScroll(scroll)

    override fun onDetachedFromWindow() {
        finishDrag(commit = false)
        super.onDetachedFromWindow()
    }

    private fun buildRow(item: SetItem): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(context.setDp(14), context.setDp(12), context.setDp(14), context.setDp(12))
        setOnClickListener {
            selectedId = item.id
            onSelect(item.id)
            syncDecorations()
        }
        val nameLine = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameLine.addView(context.setText(item.name, 17f, true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        nameLine.addView(ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = PointerOverlayDrawables.circleRippleBg(Color.parseColor("#22FFFFFF"), Color.parseColor("#33FFFFFF"))
            contentDescription = context.getString(R.string.set_edit_description, item.name)
            setOnClickListener { onEdit(item.id) }
        }, LayoutParams(context.setDp(38), context.setDp(38)).apply { leftMargin = context.setDp(6) })
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
        }, LayoutParams(context.setDp(40), context.setDp(44)).apply { leftMargin = context.setDp(6) })
        addView(nameLine)
        addView(context.setText(typeLabel(context, item.type), 13f, true).apply {
            setTextColor(Color.parseColor("#B8B8FF"))
            setPadding(0, context.setDp(4), 0, 0)
        })
        addView(context.setText(itemSummary(context, item), 13.5f).apply {
            setTextColor(Color.parseColor("#D7D7D7"))
            setPadding(0, context.setDp(4), 0, 0)
        })
        val badge = context.setText("", 12f, true).apply {
            setTextColor(Color.parseColor("#80D8A0"))
            setPadding(0, context.setDp(4), 0, 0)
            visibility = View.GONE
        }
        addView(badge)
        rowViews[item.id] = this
        badges[item.id] = badge
    }

    private fun syncDecorations() {
        rowViews.forEach { (id, row) ->
            val selected = id == selectedId
            row.background = GradientDrawable().apply {
                cornerRadius = context.setDp(16).toFloat()
                setColor(if (selected) Color.parseColor("#332E7DFF") else Color.parseColor("#1B1B1B"))
                setStroke(context.setDp(if (selected) 2 else 1), if (selected) Color.parseColor("#7E8BFF") else Color.parseColor("#2C2C2C"))
            }
            val active = runtime.active && runtime.currentItem?.id == id
            badges[id]?.apply {
                visibility = if (active) VISIBLE else GONE
                text = context.getString(if (runtime.paused) R.string.set_paused_item else R.string.set_active_item)
            }
        }
        val selectionValid = entries.any { it.id == selectedId }
        listOf(deleteButton, duplicateButton).forEach {
            it.isEnabled = selectionValid
            it.alpha = if (selectionValid) 1f else 0.45f
        }
        startButton.text = context.getString(if (runtime.active) R.string.set_cancel else R.string.set_start)
        startButton.isEnabled = true
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
