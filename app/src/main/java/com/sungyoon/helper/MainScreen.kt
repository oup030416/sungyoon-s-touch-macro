package com.sungyoon.helper

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sungyoon.helper.core.permissions.isOverlayGranted
import com.sungyoon.helper.core.permissions.isServiceEnabled
import com.sungyoon.helper.core.permissions.openAccessibilitySettings
import com.sungyoon.helper.core.permissions.openOverlaySettings
import com.sungyoon.helper.util.toast
import kotlin.math.min
import kotlin.math.roundToInt

class MainScreenView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    private val overlayStatusValue: TextView
    private val serviceStatusValue: TextView

    private var didAutoNavigateOnce = false

    init {
        // Root
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val host = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Centered, max-width content (responsive)
        val content = MaxWidthLinearLayout(context, maxWidthDp = 560).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Top bar (simple, theme-friendly)
        val title = TextView(context).apply {
            text = "성윤's 터치 매크로"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        content.addView(title)

        // 권한 상태 Card
        val permissionCard = MainUiParts.card(context, dp(14)).apply {
            val inner = MainUiParts.cardInnerColumn(context, dp(16))

            val cardTitle = MainUiParts.cardTitle(context, "권한 상태")
            inner.addView(cardTitle)

            val status1 = MainUiParts.statusRow(context, "다른 앱 위에 표시")
            val status2 = MainUiParts.statusRow(context, "접근성 권한")

            overlayStatusValue = status1.second
            serviceStatusValue = status2.second

            inner.addView(status1.first)
            inner.addView(status2.first)

            inner.addView(MainUiParts.vSpace(context, dp(10)))

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
            }

            val overlayBtn = Button(context).apply {
                text = "오버레이 설정"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener { context.openOverlaySettings() }
            }

            val accessBtn = Button(context).apply {
                text = "접근성 설정"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { context.openAccessibilitySettings() }
            }

            btnRow.addView(overlayBtn)
            btnRow.addView(accessBtn)

            inner.addView(btnRow)
            addView(inner)
        }

        content.addView(MainUiParts.vSpace(context, dp(12)))
        content.addView(permissionCard)

        // 터치포인트 지정(버튼만 남김)
        val pointerCard = MainUiParts.card(context, dp(14)).apply {
            val inner = MainUiParts.cardInnerColumn(context, dp(16))

            val cardTitle = MainUiParts.cardTitle(context, "터치포인터")
            inner.addView(cardTitle)

            val desc = TextView(context).apply {
                text = "포인터를 추가/드래그하여 위치를 지정합니다."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                alpha = 0.85f
                setPadding(0, dp(6), 0, 0)
            }
            inner.addView(desc)

            inner.addView(MainUiParts.vSpace(context, dp(12)))

            val openPointerBtn = Button(context).apply {
                text = "매크로 관리"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    val granted = isOverlayGranted(context)
                    if (!granted) {
                        context.openOverlaySettings()
                        toast(context, "오버레이 권한이 필요합니다.")
                        return@setOnClickListener
                    }
                    TouchPointerOverlay.show(context.applicationContext)
                }
            }

            inner.addView(openPointerBtn)
            addView(inner)
        }
        content.addView(pointerCard)

        content.addView(MainUiParts.vSpace(context, dp(14)))

        // Footer
        val footer = TextView(context).apply {
            text = "좌표는 픽셀(px) 기준 절대 좌표입니다. ‘매크로 관리’에서 포인터를 추가/드래그하면 자동 저장됩니다."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            alpha = 0.78f
            setPadding(dp(2), dp(6), dp(2), dp(10))
        }
        content.addView(footer)

        host.addView(content)
        scroll.addView(host)
        addView(scroll)

        // 초기 상태 표시
        refreshPermissionStateAndMaybeNavigate()
    }

    fun refreshPermissionStateAndMaybeNavigate() {
        val overlayGranted = isOverlayGranted(context)
        val serviceEnabled = isServiceEnabled(context)

        overlayStatusValue.text = if (overlayGranted) "✅" else "❌"
        serviceStatusValue.text = if (serviceEnabled) "✅" else "❌"

        // 기존 동작 유지: 최초 1회 권한 화면 유도
        if (!didAutoNavigateOnce) {
            didAutoNavigateOnce = true
            when {
                !overlayGranted -> context.openOverlaySettings()
                !serviceEnabled -> context.openAccessibilitySettings()
            }
        }
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()

    /**
     * ScrollView 안에서 "너무 넓어지는" 문제를 막기 위한 간단한 max-width 레이아웃.
     * (태블릿/폴드/가로 모드에서도 보기 좋은 반응형)
     */
    private class MaxWidthLinearLayout(
        context: Context,
        private val maxWidthDp: Int
    ) : LinearLayout(context) {

        private val density = resources.displayMetrics.density
        private val maxWidthPx: Int get() = (maxWidthDp * density).roundToInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val wMode = MeasureSpec.getMode(widthMeasureSpec)
            val wSize = MeasureSpec.getSize(widthMeasureSpec)
            val capped = if (wMode == MeasureSpec.UNSPECIFIED) maxWidthPx else min(wSize, maxWidthPx)
            val nextWidthSpec = MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST)
            super.onMeasure(nextWidthSpec, heightMeasureSpec)
        }
    }
}
