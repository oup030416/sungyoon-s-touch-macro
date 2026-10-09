package com.sungyoon.helper.overlay.set

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.sungyoon.helper.ui.DirectScrollView
import android.widget.TextView
import com.sungyoon.helper.R
import com.sungyoon.helper.overlay.pointer.PointerOverlayControlsFactory
import com.sungyoon.helper.overlay.pointer.PointerOverlayDrawables
import kotlin.math.min
import kotlin.math.roundToInt

interface SetPanelSurface {
    fun setMaxViewportHeight(px: Int)
}

internal fun Context.setDp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

internal fun Context.setText(value: CharSequence, size: Float = 14f, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

internal fun Context.setAction(
    label: String,
    color: Int = Color.parseColor("#5B5CE6"),
    onClick: () -> Unit
): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    background = PointerOverlayDrawables.roundedRippleBg(color, Color.parseColor("#40FFFFFF"), context::setDp, 14)
    minHeight = setDp(46)
    minWidth = 0
    setPadding(setDp(8), setDp(10), setDp(8), setDp(10))
    setOnClickListener { onClick() }
}

/** The root owns panel placement. This view only constrains its children. */
open class SetPanelView(
    context: Context,
    title: String,
    onBack: () -> Unit,
    backLabel: String = context.getString(R.string.set_back)
) : LinearLayout(context), SetPanelSurface {
    private var onBackClick = onBack
    private var saveStatus: TextView? = null
    private var saveFeedbackRevision = 0L
    protected val body = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, 0, 0, context.setDp(12))
    }
    protected val footer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    protected val bodyScroll = DirectScrollView(context).apply {
        isFillViewport = false
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val titleView = context.setText(title, 17f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val parentPath = context.setText("", 11.5f).apply {
        setTextColor(Color.parseColor("#B8B8B8"))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, 0, 0, context.setDp(4))
        visibility = View.GONE
    }
    private val backButton = context.setAction(backLabel, Color.parseColor("#3A3A3A")) { onBackClick() }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(parentPath)
            addView(titleView)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(backButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.setDp(8) })
    }
    private val compactBody = LinearLayout(context).apply { orientation = VERTICAL }
    private val compactScroll = DirectScrollView(context).apply {
        addView(compactBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private var maxViewportHeight = 0
    private var compact = false
    private var adapting = false
    private var pendingScrollPosition: Int? = null
    private val restoreScrollPosition = Runnable {
        if (!isLayoutRequested) applyPendingScrollPosition()
    }
    private val revealFocusedEditor = Runnable {
        if (!isAttachedToWindow || !isShown) return@Runnable
        val editor = findFocus() as? EditText ?: return@Runnable
        if (!editor.isAttachedToWindow || !editor.isShown) return@Runnable
        val margin = context.setDp(8)
        editor.requestRectangleOnScreen(Rect(0, -margin, editor.width, editor.height + margin), true)
    }

    init {
        orientation = VERTICAL
        setPadding(context.setDp(12), context.setDp(12), context.setDp(12), context.setDp(20))
        background = PointerOverlayDrawables.reservationCardBg(context::setDp)
        elevation = context.setDp(8).toFloat()
        clipToOutline = true
        isClickable = true
        isFocusable = false
        // Keeping this hierarchy mounted prevents IME focus loss during viewport changes.
        compactBody.addView(header)
        compactBody.addView(bodyScroll)
        addView(compactScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        configureScrollMode(false)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { adapt() } }
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setOnBackClick(block: () -> Unit) { onBackClick = block }

    protected fun addMinimizeAction(onMinimize: () -> Unit) {
        val button = PointerOverlayControlsFactory.createCollapseButton(context, context::setDp).apply {
            setOnClickListener { onMinimize() }
        }
        header.addView(button, header.childCount - 1, LayoutParams(context.setDp(38), context.setDp(38)).apply {
            leftMargin = context.setDp(8)
        })
    }

    protected fun addDeleteAction(onDelete: () -> Unit) {
        header.addView(context.setAction(context.getString(R.string.dialog_delete), Color.parseColor("#B93A45"), onDelete),
            header.childCount - 1, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.setDp(8)
            })
    }

    protected fun addSaveStatus(centered: Boolean = false) {
        val status = context.setText(context.getString(R.string.set_auto_saved), 12f).apply {
            gravity = if (centered) Gravity.CENTER else Gravity.START
            setTextColor(Color.parseColor("#B8B8B8"))
            setPadding(0, context.setDp(12), 0, 0)
        }
        saveStatus = status
        body.addView(status)
    }

    fun clearSaveStatus(): Long {
        saveFeedbackRevision++
        saveStatus?.apply {
            text = context.getString(R.string.set_auto_saved)
            setTextColor(Color.parseColor("#B8B8B8"))
        }
        return saveFeedbackRevision
    }

    fun showChangesSaved(revision: Long) {
        if (revision != saveFeedbackRevision) return
        saveStatus?.apply {
            text = context.getString(R.string.set_changes_saved)
            setTextColor(Color.parseColor("#80D8A0"))
        }
    }

    fun setParentPath(path: String) {
        parentPath.text = path
        parentPath.visibility = if (path.isBlank()) View.GONE else View.VISIBLE
    }

    override fun setMaxViewportHeight(px: Int) {
        if (maxViewportHeight == px.coerceAtLeast(0)) return
        maxViewportHeight = px.coerceAtLeast(0)
        post { adapt() }
    }

    protected fun bodyScrollPosition(): Int = if (compact) compactScroll.scrollY else bodyScroll.scrollY

    protected fun restoreBodyScroll(position: Int) {
        pendingScrollPosition = position.coerceAtLeast(0)
        removeCallbacks(restoreScrollPosition)
        post(restoreScrollPosition)
    }

    protected fun activeScrollView(): ScrollView = if (compact) compactScroll else bodyScroll

    protected fun clearScrollContentMinimumHeights() {
        if (body.minimumHeight != 0) body.minimumHeight = 0
        if (compactBody.minimumHeight != 0) compactBody.minimumHeight = 0
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyPendingScrollPosition()
        removeCallbacks(revealFocusedEditor)
        if (findFocus() is EditText) post(revealFocusedEditor)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(revealFocusedEditor)
        removeCallbacks(restoreScrollPosition)
        super.onDetachedFromWindow()
    }

    private fun applyPendingScrollPosition() {
        val position = pendingScrollPosition ?: return
        pendingScrollPosition = null
        activeScrollView().scrollTo(0, position)
    }

    private fun adapt() {
        if (maxViewportHeight <= 0 || adapting) return
        adapting = true
        val widthHint = (width - paddingLeft - paddingRight).takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - context.setDp(48)).coerceAtLeast(1)
        val widthSpec = MeasureSpec.makeMeasureSpec(widthHint, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        // Footer children are added after this base view is initialized.
        // Keep the real margin and the reserved height in sync on every layout.
        val footerMargin = if (footer.childCount > 0) context.setDp(12) else 0
        val footerPadding = if (footer.childCount > 0) context.setDp(4) else 0
        footer.setPadding(0, footerPadding, 0, footerPadding)
        val footerParams = footer.layoutParams as LayoutParams
        if (footerParams.topMargin != footerMargin) {
            footerParams.topMargin = footerMargin
            footer.layoutParams = footerParams
        }
        header.measure(widthSpec, heightSpec)
        footer.measure(widthSpec, heightSpec)
        val footerHeight = if (footer.childCount > 0) footer.measuredHeight + footerMargin else 0
        val fixed = paddingTop + paddingBottom + header.measuredHeight + context.setDp(10) + footerHeight
        val shouldCompact = maxViewportHeight - fixed < context.setDp(100)
        if (compact != shouldCompact) configureScrollMode(shouldCompact)
        if (compact) {
            val target = (maxViewportHeight - paddingTop - paddingBottom - footerHeight).coerceAtLeast(1)
            if (compactScroll.layoutParams.height != target) {
                compactScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, target)
            }
        } else {
            body.measure(widthSpec, heightSpec)
            val available = (min(context.setDp(450), maxViewportHeight) - fixed).coerceAtLeast(1)
            val target = min(body.measuredHeight.coerceAtLeast(context.setDp(80)), available)
            if (bodyScroll.layoutParams.height != target) {
                bodyScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, target).apply { topMargin = context.setDp(10) }
            }
        }
        adapting = false
    }

    private fun configureScrollMode(useCompact: Boolean) {
        val previousScroll = bodyScrollPosition()
        compact = useCompact
        header.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        footer.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (footer.childCount > 0) context.setDp(12) else 0
        }
        if (compact) {
            compactBody.setPadding(0, 0, 0, context.setDp(28))
            bodyScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.setDp(10)
            }
            bodyScroll.scrollTo(0, 0)
        } else {
            compactBody.setPadding(0, 0, 0, 0)
            compactScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            compactScroll.scrollTo(0, 0)
            bodyScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.setDp(270)).apply {
                topMargin = context.setDp(10)
            }
        }
        restoreBodyScroll(previousScroll)
    }
}
