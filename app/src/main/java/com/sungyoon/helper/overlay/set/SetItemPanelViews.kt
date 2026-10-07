package com.sungyoon.helper.overlay.set

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import com.sungyoon.helper.R
import com.sungyoon.helper.model.ReservationConfig
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.overlay.OverlayImeController
import com.sungyoon.helper.service.set.SetPhase
import com.sungyoon.helper.service.set.SetRunState

private fun SetPanelView.showItemPath(item: SetItem) =
    setParentPath(context.getString(R.string.set_item_path, context.getString(R.string.set_title), item.name))

internal class SetPointerPanelView(
    context: Context,
    item: SetItem,
    onBack: () -> Unit,
    onMinimize: () -> Unit,
    onClear: () -> Unit,
    onAddTap: () -> Unit,
    onAddDrag: () -> Unit,
    onPresets: () -> Unit
) : SetPanelView(context, context.getString(R.string.set_pointer_manage), onBack) {
    private val summary = context.setText("", 13.5f)

    init {
        addMinimizeAction(onMinimize)
        body.addView(context.setText(item.name, 16f, true))
        body.addView(context.setText(context.getString(R.string.set_pointer_scope_hint), 12f).apply {
            setTextColor(Color.parseColor("#B8B8B8"))
            setPadding(0, context.setDp(6), 0, context.setDp(8))
        })
        body.addView(summary)
        val options = listOf(
            context.getString(R.string.pointer_clear_all) to onClear,
            context.getString(R.string.preset_load) to onPresets,
            context.getString(R.string.pointer_add) to onAddTap,
            context.getString(R.string.pointer_add_drag) to onAddDrag
        )
        options.chunked(2).forEachIndexed { rowIndex, buttons ->
            body.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                buttons.forEachIndexed { index, (label, action) ->
                    val color = if (rowIndex == 0 && index == 0) "#8E2430" else "#4A4A4A"
                    addView(context.setAction(label, Color.parseColor(color), action),
                        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = if (index == 0) 0 else context.setDp(8) })
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(10) })
        }
        updateItem(item)
    }

    fun updateItem(item: SetItem) {
        showItemPath(item)
        summary.text = SetListPanelView.itemSummary(context, item)
    }
}

internal class SetReservationPanelView(
    context: Context,
    item: SetItem.Reserved,
    onBack: () -> Unit,
    onValue: (ReservationConfig) -> Unit,
    requestIme: (Boolean) -> Unit
) : SetPanelView(context, context.getString(R.string.set_reservation_manage), onBack) {
    private val itemId = item.id
    private val name = context.setText(item.name, 16f, true)
    private val progress = ReservationProgressView(context)
    private val inputs = ReservationInputsView(context)

    init {
        showItemPath(item)
        body.addView(name)
        body.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(8) })
        body.addView(inputs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(12) })
        addSaveStatus(centered = true)
        inputs.setValues(item.reservation)
        inputs.setOnEdited { clearSaveStatus() }
        inputs.setOnChanged(onValue)
        inputs.setOnRequestIme(requestIme)
    }

    fun updateItem(item: SetItem.Reserved) {
        showItemPath(item)
        name.text = item.name
        inputs.setValues(item.reservation)
    }

    fun renderRuntime(state: SetRunState) {
        val isCurrent = state.active && state.currentItem?.id == itemId &&
            (state.phase == SetPhase.RUN || state.phase == SetPhase.REST)
        progress.render(ReservationProgressState(
            active = isCurrent,
            paused = state.paused,
            rest = state.phase == SetPhase.REST,
            cycleCurrent = state.cycleCurrent,
            cycleTotal = if (isCurrent) state.cycleTotal else 0,
            remainingMs = if (isCurrent) state.remainingMs else 0,
            phaseDurationMs = if (isCurrent) state.phaseDurationMs else 0
        ))
    }

    fun closeIme() = inputs.closeIme()

    override fun onDetachedFromWindow() {
        inputs.cancelImeRequest()
        super.onDetachedFromWindow()
    }
}

internal class SetWaitPanelView(
    context: Context,
    item: SetItem.Wait,
    onBack: () -> Unit,
    private val onValue: (Long) -> Unit,
    requestIme: (Boolean) -> Unit
) : SetPanelView(context, context.getString(R.string.set_time), onBack) {
    private val keyboard = OverlayImeController(this, requestIme)
    private val name = context.setText(item.name, 16f, true)
    private var settingValue = false
    private var lastSentValue = item.durationMs
    private val edit = EditText(context).apply {
        setText(formatDuration(item.durationMs))
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isSingleLine = true
        gravity = Gravity.CENTER
        minHeight = context.setDp(46)
        setPadding(context.setDp(12), context.setDp(10), context.setDp(12), context.setDp(10))
        filters = arrayOf(InputFilter.LengthFilter(6))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#141414"))
            cornerRadius = context.setDp(14).toFloat()
            setStroke(context.setDp(1), Color.parseColor("#2B2B2B"))
        }
    }

    init {
        showItemPath(item)
        body.addView(name)
        body.addView(context.setText(context.getString(R.string.set_wait_label), 14f, true).apply {
            setPadding(0, context.setDp(16), 0, context.setDp(8))
        })
        body.addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(context.setText(context.getString(R.string.set_wait_hint), 12f).apply {
            setTextColor(Color.parseColor("#B8B8B8"))
            setPadding(0, context.setDp(8), 0, 0)
        })
        addSaveStatus()
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (settingValue) return
                clearSaveStatus()
                val value = parseDuration(s?.toString()) ?: return
                edit.error = null
                if (value != lastSentValue) {
                    lastSentValue = value
                    onValue(value)
                }
            }
        })
        edit.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyboard.show(edit)
            }
            false
        }
        edit.setOnClickListener { keyboard.show(edit) }
        edit.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                edit.error = if (parseDuration(edit.text?.toString()) == null) context.getString(R.string.set_wait_invalid) else null
            }
            keyboard.onFocusChanged(edit, focused)
        }
        edit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { closeIme(); true } else false
        }
    }

    fun updateItem(item: SetItem.Wait) {
        showItemPath(item)
        name.text = item.name
        if (!edit.isFocused && item.durationMs != lastSentValue) {
            lastSentValue = item.durationMs
            settingValue = true
            edit.setText(formatDuration(item.durationMs))
            settingValue = false
        }
    }

    fun closeIme() {
        if (edit.isFocused) edit.clearFocus()
        keyboard.hide()
    }

    override fun onDetachedFromWindow() {
        keyboard.detach()
        super.onDetachedFromWindow()
    }

    companion object {
        internal fun parseDuration(text: String?): Long? {
            return SetWaitDurationInput.parseSeconds(text)
        }

        private fun formatDuration(milliseconds: Long): String = SetWaitDurationInput.formatSeconds(milliseconds)
    }
}
