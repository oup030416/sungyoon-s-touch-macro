package com.sungyoon.helper.feedback

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.sungyoon.helper.BuildConfig
import com.sungyoon.helper.R
import com.sungyoon.helper.overlay.OverlayImeController
import com.sungyoon.helper.overlay.pointer.PointerOverlayDrawables
import com.sungyoon.helper.util.toast

/** A mounted editor and fixed footer stay in place when the overlay's IME viewport changes. */
internal class FeedbackPanelView(context: Context, onRequestIme: (Boolean) -> Unit) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private val keyboard = OverlayImeController(this, onRequestIme)
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = PointerOverlayDrawables.reservationCardBg(::dp)
        isClickable = true
    }
    private val editor = object : EditText(context) {
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) requestClose()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }.apply {
        hint = context.getString(R.string.feedback_hint)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#99FFFFFF"))
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        gravity = Gravity.TOP or Gravity.START
        filters = arrayOf(InputFilter.LengthFilter(FeedbackMessage.MAX_TEXT_LENGTH))
        setHorizontallyScrolling(false)
        minHeight = 0
        minLines = 1
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = PointerOverlayDrawables.roundedRippleBg(Color.parseColor("#1F1F1F"), Color.TRANSPARENT, ::dp, 12)
        setOnFocusChangeListener { _, focused -> keyboard.onFocusChanged(this, focused) }
        setOnClickListener { keyboard.show(this, selectAll = false) }
    }
    private val counter = label("", 12f).apply { gravity = Gravity.END }
    private val status = label("", 12f).apply { visibility = View.GONE }
    private val submit = button(R.string.feedback_submit, "#2E7D32") { submit() }
    private val close = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        contentDescription = context.getString(R.string.feedback_close)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = PointerOverlayDrawables.circleRippleBg(Color.parseColor("#22FFFFFF"), Color.parseColor("#33FFFFFF"))
        setPadding(dp(9), dp(9), dp(9), dp(9))
        setOnClickListener { requestClose() }
    }
    private val confirmation = FrameLayout(context).apply {
        setBackgroundColor(Color.parseColor("#BB000000"))
        isClickable = true
        visibility = View.GONE
    }
    private var generation = 0L
    private var submitting = false
    private var savedLocally = false
    private var submissionMessage: FeedbackMessage? = null
    private var afterDismiss: (() -> Unit)? = null

    init {
        visibility = View.GONE
        isClickable = true
        elevation = dp(44).toFloat()
        setBackgroundColor(Color.parseColor("#88000000"))
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(context.getString(R.string.feedback_title), 17f).apply {
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))
        }
        card.addView(header)
        card.addView(editor, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        card.addView(counter, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(submit, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, dp(480), Gravity.CENTER))
        addView(confirmation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updateCounter() }
        })
        updateCounter()
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                generation++
                editor.setText("")
                submissionMessage = null
                afterDismiss = null
            }
        })
    }

    fun isShowing() = visibility == View.VISIBLE

    fun show() {
        if (isShowing()) return
        generation++
        submitting = false
        savedLocally = false
        submissionMessage = null
        afterDismiss = null
        editor.setText("")
        editor.isEnabled = true
        close.isEnabled = true
        submit.isEnabled = FeedbackRuntime.configured
        status.visibility = if (FeedbackRuntime.configured) View.GONE else View.VISIBLE
        status.setText(R.string.feedback_unconfigured)
        confirmation.visibility = View.GONE
        visibility = View.VISIBLE
        bringToFront()
        requestLayout()
        if (FeedbackRuntime.configured) post { if (isShowing() && isAttachedToWindow) keyboard.show(editor, selectAll = false) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - dp(24)).coerceAtLeast(1)
        val availableHeight = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - dp(16)).coerceAtLeast(1)
        val lp = card.layoutParams as LayoutParams
        lp.width = availableWidth.coerceAtMost(dp(520))
        lp.height = availableHeight.coerceAtMost(dp(480))
        val compact = availableHeight < dp(260)
        val inset = dp(if (compact) 8 else 12)
        if (card.paddingTop != inset) card.setPadding(inset, inset, inset, inset)
        (editor.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (compact) 4 else 8)
        (submit.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (compact) 4 else 8)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isShowing() && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) requestClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun requestDismiss(onDismissed: () -> Unit) {
        afterDismiss = onDismissed
        requestClose()
    }

    private fun submit() {
        if (submitting || !FeedbackRuntime.configured) return
        val text = editor.text?.toString().orEmpty()
        if (text.isBlank()) {
            editor.error = context.getString(R.string.feedback_required)
            return
        }
        val message = submissionMessage ?: FeedbackMessage(text = text,
            appVersion = BuildConfig.APP_VERSION_NAME, versionCode = BuildConfig.DEV_VERSION_CODE).also { submissionMessage = it }
        val expectedGeneration = generation
        submitting = true
        editor.isEnabled = false
        submit.isEnabled = false
        close.isEnabled = false
        status.setText(R.string.feedback_saving)
        status.visibility = View.VISIBLE
        FeedbackRuntime.submit(context, message) completion@ { result ->
            if (generation != expectedGeneration || !isAttachedToWindow || !isShowing()) return@completion
            handleSubmissionResult(result)
        }
    }

    private fun handleSubmissionResult(result: FeedbackRuntime.Submission) {
        submitting = false
        close.isEnabled = true
        submit.isEnabled = true
        when (result) {
            FeedbackRuntime.Submission.Queued -> {
                dismiss()
                toast(context, context.getString(R.string.feedback_queued))
            }
            FeedbackRuntime.Submission.ScheduleFailed -> {
                // This record is already durable. Retrying must use the same ID and text.
                savedLocally = true
                editor.isEnabled = false
                status.setText(R.string.feedback_schedule_failed)
            }
            else -> {
                if (savedLocally) {
                    // A later retry error cannot turn an already stored submission into a new draft.
                    editor.isEnabled = false
                    status.setText(R.string.feedback_schedule_failed)
                } else {
                    submissionMessage = null
                    editor.isEnabled = true
                    status.setText(if (result == FeedbackRuntime.Submission.Full) R.string.feedback_queue_full else R.string.feedback_save_failed)
                }
            }
        }
    }

    private fun requestClose() {
        if (submitting) return
        if (confirmation.visibility == View.VISIBLE) {
            cancelDiscard()
            return
        }
        if (savedLocally || editor.text.isNullOrBlank()) {
            dismiss()
            return
        }
        keyboard.hide()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = PointerOverlayDrawables.reservationCardBg(::dp)
            addView(label(context.getString(R.string.feedback_discard_title), 17f))
            addView(label(context.getString(R.string.feedback_discard_message), 14f).apply { setPadding(0, dp(10), 0, dp(12)) })
            addView(LinearLayout(context).apply {
                addView(button(R.string.feedback_continue_editing, "#353535") { cancelDiscard() }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
                addView(button(R.string.feedback_discard, "#8E2430") { dismiss() }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        confirmation.removeAllViews()
        confirmation.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(24); rightMargin = dp(24)
        })
        confirmation.visibility = View.VISIBLE
        confirmation.bringToFront()
    }

    private fun cancelDiscard() {
        afterDismiss = null
        confirmation.visibility = View.GONE
        post { if (isShowing() && isAttachedToWindow) keyboard.show(editor, selectAll = false) }
    }

    private fun dismiss() {
        val continuation = afterDismiss
        afterDismiss = null
        generation++
        keyboard.hide()
        confirmation.visibility = View.GONE
        visibility = View.GONE
        editor.setText("")
        submissionMessage = null
        continuation?.invoke()
    }

    private fun updateCounter() {
        counter.text = context.getString(R.string.feedback_counter, editor.text?.length ?: 0, FeedbackMessage.MAX_TEXT_LENGTH)
    }

    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor("#DDFFFFFF"))
    }

    private fun button(label: Int, color: String, action: () -> Unit) = Button(context).apply {
        setText(label)
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = PointerOverlayDrawables.roundedRippleBg(Color.parseColor(color), Color.parseColor("#40FFFFFF"), ::dp, 12)
        setOnClickListener { action() }
    }
}
