package com.sungyoon.helper.overlay.pointer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.sungyoon.helper.R

data class PointerOverlayControlsViews(
    val controlPanel: LinearLayout,
    val addBtn: Button,
    val addDragBtn: Button,
    val presetListBtn: Button,
    val repeatToggleBtn: Button,
    val playToggleBtn: Button,
    val reserveBtn: Button,
    val touchAnimToggleBtn: Button,
    val collapseBtn: ImageButton,
    val closeBtn: ImageButton,
    val intervalEdit: EditText,
    val dragDurationEdit: EditText,
    val randomRadiusSeek: SeekBar,
    val randomRadiusValueText: TextView,
    val pointerSizeSeek: SeekBar,
    val pointerSizeValueText: TextView,
    val subtitleText: TextView,
    val hintText: TextView
)

object PointerOverlayControlsFactory {

    private val PLAY_STANDBY_FILL_COLOR = Color.parseColor("#664CAF50")

    fun create(
        context: Context,
        dp: (Int) -> Int
    ): PointerOverlayControlsViews {

        val controlPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(12)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = PointerOverlayDrawables.cardBg(dp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(8).toFloat()
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val titleText = TextView(context).apply {
            text = context.getString(R.string.pointer_control_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val subtitleText = TextView(context).apply {
            text = context.getString(R.string.pointer_control_subtitle)
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(3), 0, 0)
        }

        val collapseBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.arrow_up_float)
            contentDescription = context.getString(R.string.pointer_control_hide)
            background = PointerOverlayDrawables.circleRippleBg(
                baseColor = Color.parseColor("#22FFFFFF"),
                rippleColor = Color.parseColor("#33FFFFFF")
            )
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                rightMargin = dp(8)
            }
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }

        val closeBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = context.getString(R.string.pointer_panel_close)
            background = PointerOverlayDrawables.circleRippleBg(
                baseColor = Color.parseColor("#22FFFFFF"),
                rippleColor = Color.parseColor("#33FFFFFF")
            )
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }

        titleCol.addView(titleText)
        titleCol.addView(subtitleText)
        headerRow.addView(titleCol)
        headerRow.addView(collapseBtn)
        headerRow.addView(closeBtn)

        val hintText = TextView(context).apply {
            text = context.getString(R.string.pointer_control_hint)
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
        }

        val intervalRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val intervalLabel = TextView(context).apply {
            text = context.getString(R.string.pointer_interval_label)
            setTextColor(Color.parseColor("#E6FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val inputBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#1FFFFFFF"))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }

        val intervalEdit = EditText(context).apply {
            setText("1.0")
            setSelection(text?.length ?: 0)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            hint = "1.0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(InputFilter.LengthFilter(5), OneDecimalSecondsFilter())
            gravity = Gravity.CENTER
            background = inputBg.constantState?.newDrawable()?.mutate()
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
            minHeight = dp(42)
            isFocusableInTouchMode = true
        }

        intervalRow.addView(intervalLabel)
        intervalRow.addView(intervalEdit)

        val dragDurationRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragDurationLabel = TextView(context).apply {
            text = context.getString(R.string.pointer_drag_duration_label)
            setTextColor(Color.parseColor("#E6FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dragDurationEdit = EditText(context).apply {
            setText("1.0")
            setSelection(text?.length ?: 0)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            hint = context.getString(R.string.pointer_drag_duration_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(InputFilter.LengthFilter(5), OneDecimalSecondsFilter())
            gravity = Gravity.CENTER
            background = inputBg.constantState?.newDrawable()?.mutate()
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
            minHeight = dp(42)
            isFocusableInTouchMode = true
        }

        dragDurationRow.addView(dragDurationLabel)
        dragDurationRow.addView(dragDurationEdit)

        val randomRadiusRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val randomRadiusTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val randomRadiusLabel = TextView(context).apply {
            text = context.getString(R.string.pointer_random_radius_label)
            setTextColor(Color.parseColor("#E6FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val randomRadiusValueText = TextView(context).apply {
            text = context.getString(R.string.pointer_random_radius_value, 5)
            setTextColor(Color.parseColor("#E6FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        randomRadiusTop.addView(randomRadiusLabel)
        randomRadiusTop.addView(randomRadiusValueText)

        val randomRadiusSeek = SeekBar(context).apply {
            max = 20
            progress = 5
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        randomRadiusRow.addView(randomRadiusTop)
        randomRadiusRow.addView(randomRadiusSeek)

        val pointerSizeSeek = SeekBar(context).apply {
            max = 9
            progress = 7
            visibility = View.GONE
        }
        val pointerSizeValueText = TextView(context).apply {
            text = "8"
            visibility = View.GONE
        }

        val actionsCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        fun actionButton(text: String, fillColor: Int): Button {
            return Button(context).apply {
                this.text = text
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = PointerOverlayDrawables.roundedRippleBg(
                    fillColor = fillColor,
                    rippleColor = Color.parseColor("#33FFFFFF"),
                    dp = dp,
                    radiusDp = 14
                )
                setPadding(dp(12), dp(11), dp(12), dp(11))
                minHeight = dp(42)
            }
        }

        val addBtn = actionButton(
            text = context.getString(R.string.pointer_add),
            fillColor = Color.parseColor("#5B5CE6")
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val addDragBtn = actionButton(
            text = context.getString(R.string.pointer_add_drag),
            fillColor = Color.parseColor("#5B5CE6")
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val presetListBtn = actionButton(
            text = context.getString(R.string.pointer_preset_list),
            fillColor = Color.parseColor("#4D5B5CE6")
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            }
        }

        val playToggleBtn = actionButton(
            text = context.getString(R.string.pointer_play_start),
            fillColor = Color.parseColor("#334CAF50")
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            }
        }

        val reserveBtn = actionButton(
            text = context.getString(R.string.reservation_title),
            fillColor = PLAY_STANDBY_FILL_COLOR
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val touchAnimToggleBtn = actionButton(
            text = context.getString(R.string.pointer_touch_animation_on),
            fillColor = PLAY_STANDBY_FILL_COLOR
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            }
        }

        val repeatToggleBtn = actionButton(
            text = context.getString(R.string.pointer_repeat_on),
            fillColor = Color.parseColor("#2FFFFFFF")
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row1.addView(touchAnimToggleBtn)
        row2.addView(presetListBtn)
        row2.addView(addBtn)
        row2.addView(addDragBtn)
        row3.addView(reserveBtn)
        row3.addView(playToggleBtn)

        actionsCol.addView(row1)
        actionsCol.addView(row2)
        actionsCol.addView(row3)

        controlPanel.addView(headerRow)
        controlPanel.addView(hintText)
        controlPanel.addView(randomRadiusRow)
        controlPanel.addView(intervalRow)
        controlPanel.addView(dragDurationRow)
        controlPanel.addView(actionsCol)

        return PointerOverlayControlsViews(
            controlPanel = controlPanel,
            addBtn = addBtn,
            addDragBtn = addDragBtn,
            presetListBtn = presetListBtn,
            repeatToggleBtn = repeatToggleBtn,
            playToggleBtn = playToggleBtn,
            reserveBtn = reserveBtn,
            touchAnimToggleBtn = touchAnimToggleBtn,
            collapseBtn = collapseBtn,
            closeBtn = closeBtn,
            intervalEdit = intervalEdit,
            dragDurationEdit = dragDurationEdit,
            randomRadiusSeek = randomRadiusSeek,
            randomRadiusValueText = randomRadiusValueText,
            pointerSizeSeek = pointerSizeSeek,
            pointerSizeValueText = pointerSizeValueText,
            subtitleText = subtitleText,
            hintText = hintText
        )
    }

    private class OneDecimalSecondsFilter : InputFilter {
        private val regex = Regex("^\\d{0,4}([.]\\d{0,1})?$")

        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val src = source?.subSequence(start, end)?.toString().orEmpty()
            val before = dest?.toString().orEmpty()
            val next = buildString {
                append(before.substring(0, dstart))
                append(src)
                append(before.substring(dend))
            }
            return if (regex.matches(next)) null else ""
        }
    }
}
