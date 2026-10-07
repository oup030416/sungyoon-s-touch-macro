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
import com.sungyoon.helper.overlay.OverlayImeController
import com.sungyoon.helper.util.toast

/** Store-independent reservation fields shared by both reservation editors. */
class ReservationInputsView(context: Context) : LinearLayout(context) {
    private val runEdit = numericField(60, 3600)
    private val restEdit = numericField(30, 3600)
    private val repeatEdit = numericField(1, 9999)
    private var updating = false
    private var locked = false
    private var onChanged: ((ReservationConfig) -> Unit)? = null
    private var requestIme: ((Boolean) -> Unit)? = null
    private val keyboard = OverlayImeController(this) { requestIme?.invoke(it) }
    private val resetButton = context.setAction(context.getString(R.string.reservation_reset_inputs), Color.parseColor("#2A2A2A")) {
        if (locked) {
            toast(context, context.getString(R.string.reservation_locked_message))
        } else {
            setValues(ReservationConfig(), force = true)
            closeIme()
            readValues()?.let { onChanged?.invoke(it) }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(fieldRow(runEdit, R.string.reservation_run_seconds))
        addView(fieldRow(restEdit, R.string.reservation_rest_seconds), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.setDp(8)
        })
        addView(fieldRow(repeatEdit, R.string.reservation_repeat_count), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.setDp(8)
        })
        addView(resetButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.setDp(12) })
        listOf(runEdit, restEdit, repeatEdit).forEach { edit ->
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!updating && !locked) readValues()?.let { onChanged?.invoke(it) }
                }
            })
            edit.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (locked) {
                        toast(context, context.getString(R.string.reservation_locked_message))
                        return@setOnTouchListener true
                    }
                    keyboard.show(edit)
                }
                false
            }
            edit.setOnClickListener {
                if (locked) toast(context, context.getString(R.string.reservation_locked_message))
                else keyboard.show(edit)
            }
            edit.setOnFocusChangeListener { _, focused ->
                if (!focused) validateField(edit)
                keyboard.onFocusChanged(edit, focused)
            }
            edit.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    closeIme()
                    true
                } else false
            }
        }
    }

    fun setOnChanged(block: (ReservationConfig) -> Unit) { onChanged = block }

    fun setOnRequestIme(block: (Boolean) -> Unit) { requestIme = block }

    fun setValues(config: ReservationConfig, force: Boolean = false) {
        updating = true
        listOf(runEdit to config.runSeconds, restEdit to config.restSeconds, repeatEdit to config.repeatCount).forEach { (edit, value) ->
            if ((force || !edit.isFocused) && edit.text?.toString() != value.toString()) {
                edit.setText(value.toString())
                edit.setSelection(edit.text?.length ?: 0)
                edit.error = null
            }
        }
        updating = false
    }

    fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        listOf(runEdit, restEdit, repeatEdit).forEach { edit ->
            if (locked && edit.isFocused) closeIme()
            edit.isFocusable = !locked
            edit.isFocusableInTouchMode = !locked
            edit.isCursorVisible = !locked
            edit.isLongClickable = !locked
            edit.alpha = if (locked) 0.55f else 1f
        }
        resetButton.alpha = if (locked) 0.55f else 1f
    }

    fun readValues(): ReservationConfig? {
        val run = getRunSecondsOrNull()?.takeIf { it in 1..3600 } ?: return null
        val rest = getRestSecondsOrNull()?.takeIf { it in 1..3600 } ?: return null
        val repeats = getRepeatCountOrNull()?.takeIf { it in 1..9999 } ?: return null
        return ReservationConfig(run, rest, repeats)
    }

    fun getRunSecondsOrNull(): Int? = runEdit.text?.toString()?.toIntOrNull()
    fun getRestSecondsOrNull(): Int? = restEdit.text?.toString()?.toIntOrNull()
    fun getRepeatCountOrNull(): Int? = repeatEdit.text?.toString()?.toIntOrNull()

    fun closeIme() {
        listOf(runEdit, restEdit, repeatEdit).forEach { edit ->
            if (edit.isFocused) edit.clearFocus()
        }
        keyboard.hide()
    }

    fun cancelImeRequest() = keyboard.detach()

    override fun onDetachedFromWindow() {
        keyboard.detach()
        super.onDetachedFromWindow()
    }

    private fun fieldRow(edit: EditText, label: Int): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(edit, LayoutParams(context.setDp(88), LayoutParams.WRAP_CONTENT))
        addView(context.setText(context.getString(label), 14f, true).apply { setPadding(context.setDp(10), 0, 0, 0) })
    }

    private fun numericField(defaultValue: Int, maximum: Int): EditText = EditText(context).apply {
        setText(defaultValue.toString())
        hint = defaultValue.toString()
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#9E9E9E"))
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        inputType = InputType.TYPE_CLASS_NUMBER
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isSingleLine = true
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#141414"))
            cornerRadius = context.setDp(14).toFloat()
            setStroke(context.setDp(1), Color.parseColor("#2B2B2B"))
        }
        setPadding(context.setDp(12), context.setDp(9), context.setDp(12), context.setDp(9))
        minHeight = context.setDp(42)
        filters = arrayOf(InputFilter.LengthFilter(4))
        tag = maximum
    }

    private fun validateField(edit: EditText) {
        val max = edit.tag as Int
        edit.error = if (edit.text?.toString()?.toIntOrNull()?.let { it in 1..max } == true) null
            else context.getString(R.string.reservation_input_invalid, max)
    }

}
