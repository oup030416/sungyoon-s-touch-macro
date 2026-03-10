package com.sungyoon.helper.overlay.pointer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sungyoon.helper.R
import com.sungyoon.helper.util.toast

class PointerOverlayModalHostView(
    context: Context,
    private val dp: (Int) -> Int,
    private val onRequestIme: (Boolean) -> Unit
) : FrameLayout(context) {

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = PointerOverlayDrawables.reservationCardBg(dp)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        isClickable = true
    }

    private var inputEdit: EditText? = null

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.parseColor("#88000000"))
        isClickable = true
        visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(40).toFloat()
            translationZ = dp(40).toFloat()
        }

        addView(
            card,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
            }
        )
    }

    fun isShowing(): Boolean = visibility == View.VISIBLE

    fun dismiss() {
        hideKeyboard()
        inputEdit = null
        onRequestIme(false)
        card.removeAllViews()
        visibility = View.GONE
    }

    fun showConfirmationDialog(
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        dismiss()
        bringToFront()
        visibility = View.VISIBLE
        requestLayout()
        invalidate()

        card.addView(titleText(title))
        card.addView(
            bodyText(message).apply {
                setPadding(0, dp(10), 0, 0)
            }
        )
        val confirmColor = if (destructive) {
            Color.parseColor("#8E2430")
        } else {
            Color.parseColor("#5B5CE6")
        }
        card.addView(buttonRow(cancelText, confirmText, confirmColor = confirmColor) {
            onConfirm()
            dismiss()
        })
    }

    fun showInputDialog(
        title: String,
        initialValue: String,
        hint: String,
        confirmText: String,
        cancelText: String,
        onSubmit: (String) -> Unit
    ) {
        dismiss()
        bringToFront()
        visibility = View.VISIBLE
        requestLayout()
        invalidate()

        val edit = EditText(context).apply {
            setText(initialValue)
            setSelection(text?.length ?: 0)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7FFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PERSON_NAME or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
            maxLines = 1
            minLines = 1
            isSingleLine = true
            setHorizontallyScrolling(false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = PointerOverlayDrawables.roundedRippleBg(
                fillColor = Color.parseColor("#1F1F1F"),
                rippleColor = Color.parseColor("#00000000"),
                dp = dp,
                radiusDp = 14
            )
            this.hint = hint
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    if (hasComposingText()) {
                        false
                    } else {
                        submitInput(onSubmit)
                        true
                    }
                } else {
                    false
                }
            }
        }
        inputEdit = edit

        card.addView(titleText(title))
        card.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
        card.addView(buttonRow(cancelText, confirmText, confirmColor = Color.parseColor("#5B5CE6")) {
            submitInput(onSubmit)
        })

        onRequestIme(true)
        post {
            edit.requestFocus()
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitInput(onSubmit: (String) -> Unit) {
        val edit = inputEdit ?: return
        val value = edit.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) {
            toast(context, context.getString(R.string.dialog_name_required))
            return
        }
        onSubmit(value)
        dismiss()
    }

    private fun hasComposingText(): Boolean {
        val edit = inputEdit ?: return false
        val text = edit.text ?: return false
        return BaseInputConnection.getComposingSpanStart(text) >= 0 &&
                BaseInputConnection.getComposingSpanEnd(text) >= 0
    }

    private fun hideKeyboard() {
        try {
            val token = inputEdit?.windowToken ?: windowToken
            if (token != null) {
                imm.hideSoftInputFromWindow(token, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun titleText(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    private fun bodyText(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#D7D7D7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
    }

    private fun buttonRow(
        cancelText: String,
        confirmText: String,
        confirmColor: Int,
        onConfirm: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cancelBtn = actionButton(
            text = cancelText,
            fillColor = Color.parseColor("#353535")
        ) {
            dismiss()
        }
        val confirmBtn = actionButton(
            text = confirmText,
            fillColor = confirmColor
        ) {
            onConfirm()
        }

        row.addView(cancelBtn)
        row.addView(confirmBtn)
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        }
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
            minHeight = dp(44)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = dp(6)
                leftMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }
}
