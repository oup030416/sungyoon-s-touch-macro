package com.sungyoon.helper.overlay.set

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import com.sungyoon.helper.R
import com.sungyoon.helper.model.SetDefinition
import com.sungyoon.helper.overlay.pointer.PointerOverlayDrawables

/** Catalog ordering is saved separately from the ID-bound set editor and execution. */
class SetCatalogPanelView(
    context: Context,
    onBack: () -> Unit,
    private val onOpen: (String) -> Unit,
    private val onRename: (String) -> Unit,
    private val onDuplicate: (String) -> Unit,
    onAdd: () -> Unit,
    private val onMove: (String, Int) -> Unit,
) : SetPanelView(context, context.getString(R.string.set_catalog_title), onBack) {
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val rowViews = linkedMapOf<String, LinearLayout>()
    private var entries = emptyList<SetDefinition>()
    private var pendingRender: Pair<List<SetDefinition>, String?>? = null
    private var dragId: String? = null
    private var dragOrigin = 0
    private var dragTarget = 0
    private var downY = 0f
    private var rawY = 0f
    private var downScroll = 0
    private var centerY = 0f
    private val dragFrame = object : Runnable {
        override fun run() {
            if (dragId == null) return
            val scroll = activeScrollView()
            val location = IntArray(2)
            scroll.getLocationOnScreen(location)
            val edge = context.setDp(44)
            val movement = when {
                rawY < location[1] + edge -> -context.setDp(7)
                rawY > location[1] + scroll.height - edge -> context.setDp(7)
                else -> 0
            }
            if (movement != 0) { scroll.scrollBy(0, movement); previewDrag() }
            postOnAnimation(this)
        }
    }

    init {
        body.addView(rows)
        body.addView(context.setAction(context.getString(R.string.set_catalog_add), Color.parseColor("#4A4A4A"), onAdd),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(12) })
    }

    fun savedScrollPosition(): Int = bodyScrollPosition()
    fun restoreSavedScrollPosition(position: Int) = restoreBodyScroll(position)

    fun render(sets: List<SetDefinition>, selectedId: String?) {
        if (dragId != null) { pendingRender = sets to selectedId; return }
        val scroll = savedScrollPosition()
        entries = sets
        rowViews.clear()
        rows.removeAllViews()
        if (sets.isEmpty()) rows.addView(context.setText(context.getString(R.string.set_catalog_empty), 13f))
        sets.forEach { set ->
            val selected = set.id == selectedId
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.setDp(12), context.setDp(10), context.setDp(8), context.setDp(10))
                background = PointerOverlayDrawables.roundedRippleBg(
                    Color.parseColor(if (selected) "#273758" else "#252525"), Color.parseColor("#40FFFFFF"), context::setDp, 14)
                setOnClickListener { onOpen(set.id) }
            }
            row.addView(context.setText("≡", 26f, true).apply {
                gravity = Gravity.CENTER
                contentDescription = context.getString(R.string.set_reorder_description, set.name)
                isClickable = true
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> beginDrag(set.id, event.rawY)
                        MotionEvent.ACTION_MOVE -> { rawY = event.rawY; previewDrag() }
                        MotionEvent.ACTION_UP -> {
                            rawY = event.rawY
                            previewDrag()
                            view.performClick()
                            finishDrag(true)
                        }
                        MotionEvent.ACTION_CANCEL -> finishDrag(false)
                    }
                    true
                }
            }, LayoutParams(context.setDp(32), context.setDp(44)).apply { rightMargin = context.setDp(6) })
            row.addView(context.setText(set.name, 14f, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_set_duplicate)
                setColorFilter(Color.WHITE)
                contentDescription = context.getString(R.string.set_duplicate_description, set.name)
                background = PointerOverlayDrawables.roundedRippleBg(Color.parseColor("#4F54BF"), Color.parseColor("#40FFFFFF"), context::setDp, 12)
                setPadding(context.setDp(7), context.setDp(7), context.setDp(7), context.setDp(7))
                setOnClickListener { onDuplicate(set.id) }
            }, LayoutParams(context.setDp(38), context.setDp(38)).apply { leftMargin = context.setDp(8) })
            row.addView(ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_edit)
                setColorFilter(Color.WHITE)
                contentDescription = context.getString(R.string.set_rename)
                background = PointerOverlayDrawables.roundedRippleBg(Color.parseColor("#3A3A3A"), Color.parseColor("#40FFFFFF"), context::setDp, 12)
                setPadding(context.setDp(7), context.setDp(7), context.setDp(7), context.setDp(7))
                setOnClickListener { onRename(set.id) }
            }, LayoutParams(context.setDp(38), context.setDp(38)).apply { leftMargin = context.setDp(8) })
            rowViews[set.id] = row
            rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.setDp(8) })
        }
        restoreSavedScrollPosition(scroll)
    }

    override fun onDetachedFromWindow() {
        finishDrag(false)
        super.onDetachedFromWindow()
    }

    private fun beginDrag(id: String, y: Float) {
        val row = rowViews[id] ?: return
        dragId = id
        dragOrigin = entries.indexOfFirst { it.id == id }
        dragTarget = dragOrigin
        downY = y
        rawY = y
        downScroll = activeScrollView().scrollY
        centerY = row.top + row.height / 2f
        row.elevation = context.setDp(12).toFloat()
        activeScrollView().requestDisallowInterceptTouchEvent(true)
        postOnAnimation(dragFrame)
    }

    private fun previewDrag() {
        val id = dragId ?: return
        val source = rowViews[id] ?: return
        val delta = rawY - downY + activeScrollView().scrollY - downScroll
        val center = centerY + delta
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
        entries.forEachIndexed { index, set ->
            rowViews[set.id]?.translationY = when {
                set.id == id -> delta
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
        pendingRender?.let { pendingRender = null; render(it.first, it.second) }
        if (commit && dragTarget != dragOrigin) onMove(id, dragTarget)
    }
}
