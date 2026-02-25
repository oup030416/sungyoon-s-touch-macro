package com.sungyoon.helper

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

object MainUiParts {

    fun card(context: Context, radiusPx: Int): LinearLayout {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()

            // theme 기반 배경을 우선 시도 (없으면 fallback)
            setColor(resolveColor(context, android.R.attr.colorBackgroundFloating, Color.parseColor("#FFFFFF")))
            setStroke(dp(context, 1), Color.parseColor("#1F000000"))
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = bg
            // 기본 기능만 사용: elevation은 API21+에서만 의미있지만 안전
            if (android.os.Build.VERSION.SDK_INT >= 21) elevation = dp(context, 2).toFloat()
        }
    }

    fun cardInnerColumn(context: Context, paddingPx: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
    }

    fun cardTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    /**
     * @return Pair(rowView, valueTextView)
     */
    fun statusRow(context: Context, label: String): Pair<View, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val labelTv = TextView(context).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val valueTv = TextView(context).apply {
            text = "❌"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }

        row.addView(labelTv)
        row.addView(valueTv)
        return row to valueTv
    }

    fun vSpace(context: Context, heightPx: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx
            )
        }
    }

    private fun dp(context: Context, v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).roundToInt()
    }

    private fun resolveColor(context: Context, attr: Int, fallback: Int): Int {
        val out = TypedValue()
        return if (context.theme.resolveAttribute(attr, out, true)) {
            if (out.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) out.data else fallback
        } else fallback
    }
}
