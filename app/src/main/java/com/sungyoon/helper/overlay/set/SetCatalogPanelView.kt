package com.sungyoon.helper.overlay.set

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import com.sungyoon.helper.R
import com.sungyoon.helper.model.SetDefinition
import com.sungyoon.helper.overlay.pointer.PointerOverlayDrawables

/** Catalog navigation has its own fixed footer and never changes item ordering. */
class SetCatalogPanelView(
    context: Context,
    onBack: () -> Unit,
    private val onOpen: (String) -> Unit,
    private val onRename: (String) -> Unit,
    private val onDuplicate: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
) : SetPanelView(context, context.getString(R.string.set_catalog_title), onBack) {
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val delete = context.setAction(context.getString(R.string.dialog_delete), Color.parseColor("#B93A45"), onDelete)

    init {
        body.addView(rows)
        body.addView(context.setAction(context.getString(R.string.set_catalog_add), Color.parseColor("#4A4A4A"), onAdd),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(12) })
        footer.addView(delete, LayoutParams(context.setDp(80), LayoutParams.WRAP_CONTENT))
    }

    fun savedScrollPosition(): Int = bodyScrollPosition()
    fun restoreSavedScrollPosition(position: Int) = restoreBodyScroll(position)

    fun render(sets: List<SetDefinition>, selectedId: String?) {
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
            row.addView(context.setText(set.name, 14f, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_edit)
                setColorFilter(Color.WHITE)
                contentDescription = context.getString(R.string.set_rename)
                background = PointerOverlayDrawables.roundedRippleBg(Color.parseColor("#3A3A3A"), Color.parseColor("#40FFFFFF"), context::setDp, 12)
                setPadding(context.setDp(7), context.setDp(7), context.setDp(7), context.setDp(7))
                setOnClickListener { onRename(set.id) }
            }, LayoutParams(context.setDp(38), context.setDp(38)).apply { leftMargin = context.setDp(8) })
            row.addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_set_duplicate)
                setColorFilter(Color.WHITE)
                contentDescription = context.getString(R.string.set_duplicate_description, set.name)
                background = PointerOverlayDrawables.roundedRippleBg(Color.parseColor("#4F54BF"), Color.parseColor("#40FFFFFF"), context::setDp, 12)
                setPadding(context.setDp(7), context.setDp(7), context.setDp(7), context.setDp(7))
                setOnClickListener { onDuplicate(set.id) }
            }, LayoutParams(context.setDp(38), context.setDp(38)).apply { leftMargin = context.setDp(8) })
            rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.setDp(8) })
        }
        delete.isEnabled = sets.any { it.id == selectedId }
        delete.alpha = if (delete.isEnabled) 1f else 0.4f
    }
}
