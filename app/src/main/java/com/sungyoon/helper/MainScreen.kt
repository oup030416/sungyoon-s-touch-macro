package com.sungyoon.helper

import android.app.Activity
import android.graphics.Color
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sungyoon.helper.core.permissions.isOverlayGranted
import com.sungyoon.helper.core.permissions.isServiceEnabled
import com.sungyoon.helper.core.permissions.openAccessibilitySettings
import com.sungyoon.helper.core.permissions.openOverlaySettings
import com.sungyoon.helper.update.AppUpdateCheckResult
import com.sungyoon.helper.update.AppUpdateDownloadProgress
import com.sungyoon.helper.update.AppUpdateInfo
import com.sungyoon.helper.update.AppUpdateManager
import com.sungyoon.helper.util.toast
import kotlin.math.min
import kotlin.math.roundToInt

class MainScreenView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    private val overlayStatusValue: TextView
    private val serviceStatusValue: TextView
    private val updateVersionValue: TextView
    private val updateStatusValue: TextView
    private val updateDetailValue: TextView
    private val updateProgressBar: ProgressBar
    private val updateProgressValue: TextView
    private val checkUpdateButton: Button
    private val installUpdateButton: Button

    private var didAutoNavigateOnce = false
    private var updateUiState: UpdateUiState = UpdateUiState.Idle
    private var latestUpdateInfo: AppUpdateInfo? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressPollRunnable = object : Runnable {
        override fun run() {
            val progress = AppUpdateManager.getDownloadProgress(context)
            if (progress != null) {
                updateUiState = UpdateUiState.Downloading(progress)
                renderUpdateState()
                mainHandler.postDelayed(this, 500L)
            } else {
                if (updateUiState is UpdateUiState.Downloading) {
                    updateUiState = latestUpdateInfo?.let { UpdateUiState.Outdated(it) } ?: UpdateUiState.Idle
                    renderUpdateState()
                }
            }
        }
    }

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
            text = context.getString(R.string.main_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        content.addView(title)

        // 권한 상태 Card
        val permissionCard = MainUiParts.card(context, dp(14)).apply {
            val inner = MainUiParts.cardInnerColumn(context, dp(16))

            val cardTitle = MainUiParts.cardTitle(context, context.getString(R.string.main_permission_title))
            inner.addView(cardTitle)

            val status1 = MainUiParts.statusRow(context, context.getString(R.string.main_permission_overlay))
            val status2 = MainUiParts.statusRow(context, context.getString(R.string.main_permission_accessibility))

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
                text = context.getString(R.string.main_overlay_settings)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener { context.openOverlaySettings() }
            }

            val accessBtn = Button(context).apply {
                text = context.getString(R.string.main_accessibility_settings)
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

            val cardTitle = MainUiParts.cardTitle(context, context.getString(R.string.main_pointer_title))
            inner.addView(cardTitle)

            val desc = TextView(context).apply {
                text = context.getString(R.string.main_pointer_description)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                alpha = 0.85f
                setPadding(0, dp(6), 0, 0)
            }
            inner.addView(desc)

            inner.addView(MainUiParts.vSpace(context, dp(12)))

            val openPointerBtn = Button(context).apply {
                text = context.getString(R.string.main_pointer_manage)
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
            text = context.getString(R.string.main_footer)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            alpha = 0.78f
            setPadding(dp(2), dp(6), dp(2), dp(10))
        }
        content.addView(footer)

        content.addView(MainUiParts.vSpace(context, dp(8)))

        val updateCard = MainUiParts.card(context, dp(14)).apply {
            val inner = MainUiParts.cardInnerColumn(context, dp(16))

            val cardTitle = MainUiParts.cardTitle(context, context.getString(R.string.update_card_title))
            inner.addView(cardTitle)

            updateVersionValue = TextView(context).apply {
                text = context.getString(R.string.update_current_version, BuildConfig.APP_VERSION_NAME)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(4))
            }
            inner.addView(updateVersionValue)

            updateStatusValue = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            inner.addView(updateStatusValue)

            updateDetailValue = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.86f
                setPadding(0, dp(6), 0, 0)
            }
            inner.addView(updateDetailValue)

            updateProgressBar = ProgressBar(
                context,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(10)
                ).apply {
                    topMargin = dp(10)
                }
                max = 100
                progress = 0
                visibility = View.GONE
            }
            inner.addView(updateProgressBar)

            updateProgressValue = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
                visibility = View.GONE
            }
            inner.addView(updateProgressValue)

            inner.addView(MainUiParts.vSpace(context, dp(12)))

            checkUpdateButton = Button(context).apply {
                text = context.getString(R.string.update_check_button)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { handleCheckForUpdates() }
            }
            inner.addView(checkUpdateButton)

            installUpdateButton = Button(context).apply {
                text = context.getString(R.string.update_install_button)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
                visibility = View.GONE
                setOnClickListener {
                    latestUpdateInfo?.let {
                        AppUpdateManager.enqueueDownload(context, it)
                        refreshUpdateProgress()
                    }
                }
            }
            inner.addView(installUpdateButton)

            addView(inner)
        }
        content.addView(updateCard)

        host.addView(content)
        scroll.addView(host)
        addView(scroll)

        // 초기 상태 표시
        refreshPermissionStateAndMaybeNavigate()
        refreshUpdateProgress()
        renderUpdateState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshUpdateProgress()
    }

    override fun onDetachedFromWindow() {
        stopProgressPolling()
        super.onDetachedFromWindow()
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

    fun refreshUpdateProgress() {
        val progress = AppUpdateManager.getDownloadProgress(context)
        if (progress != null) {
            updateUiState = UpdateUiState.Downloading(progress)
            renderUpdateState()
            startProgressPolling()
        } else if (updateUiState is UpdateUiState.Downloading) {
            updateUiState = latestUpdateInfo?.let { UpdateUiState.Outdated(it) } ?: UpdateUiState.Idle
            renderUpdateState()
            stopProgressPolling()
        }
    }

    private fun handleCheckForUpdates() {
        val activity = context as? Activity ?: return
        updateUiState = UpdateUiState.Checking
        renderUpdateState()

        AppUpdateManager.checkForUpdates(activity) { result ->
            updateUiState = when (result) {
                AppUpdateCheckResult.Offline -> {
                    latestUpdateInfo = null
                    UpdateUiState.Offline
                }

                AppUpdateCheckResult.Error -> {
                    latestUpdateInfo = null
                    UpdateUiState.Error
                }

                is AppUpdateCheckResult.UpToDate -> {
                    latestUpdateInfo = null
                    UpdateUiState.UpToDate(result.info.versionName)
                }

                is AppUpdateCheckResult.UpdateAvailable -> {
                    latestUpdateInfo = result.info
                    UpdateUiState.Outdated(result.info)
                }
            }
            renderUpdateState()
        }
    }

    private fun renderUpdateState() {
        val neutral = resolveColor(android.R.attr.textColorSecondary, Color.parseColor("#6E6E73"))
        val positive = Color.parseColor("#1E8E3E")
        val negative = Color.parseColor("#C62828")

        when (val state = updateUiState) {
            UpdateUiState.Idle -> {
                updateStatusValue.setTextColor(neutral)
                updateStatusValue.text = context.getString(R.string.update_status_idle)
                updateDetailValue.visibility = View.GONE
                updateProgressBar.visibility = View.GONE
                updateProgressValue.visibility = View.GONE
                installUpdateButton.visibility = View.GONE
                checkUpdateButton.isEnabled = true
            }

            UpdateUiState.Checking -> {
                updateStatusValue.setTextColor(neutral)
                updateStatusValue.text = context.getString(R.string.update_status_checking)
                updateDetailValue.visibility = View.GONE
                updateProgressBar.visibility = View.GONE
                updateProgressValue.visibility = View.GONE
                installUpdateButton.visibility = View.GONE
                checkUpdateButton.isEnabled = false
            }

            is UpdateUiState.UpToDate -> {
                updateStatusValue.setTextColor(positive)
                updateStatusValue.text = context.getString(R.string.update_status_latest)
                updateDetailValue.visibility = View.VISIBLE
                updateDetailValue.text = context.getString(R.string.update_latest_version, state.versionName)
                updateProgressBar.visibility = View.GONE
                updateProgressValue.visibility = View.GONE
                installUpdateButton.visibility = View.GONE
                checkUpdateButton.isEnabled = true
            }

            is UpdateUiState.Outdated -> {
                updateStatusValue.setTextColor(negative)
                updateStatusValue.text = context.getString(R.string.update_status_outdated)
                updateDetailValue.visibility = View.VISIBLE
                updateDetailValue.text = context.getString(R.string.update_latest_version, state.info.versionName)
                updateProgressBar.visibility = View.GONE
                updateProgressValue.visibility = View.GONE
                installUpdateButton.visibility = View.VISIBLE
                checkUpdateButton.isEnabled = true
            }

            is UpdateUiState.Downloading -> {
                updateStatusValue.setTextColor(negative)
                updateStatusValue.text = context.getString(R.string.update_status_downloading)
                updateDetailValue.visibility = View.VISIBLE
                updateDetailValue.text = context.getString(
                    R.string.update_progress_bytes,
                    formatMegabytes(state.progress.downloadedBytes),
                    formatMegabytes(state.progress.totalBytes)
                )
                updateProgressBar.visibility = View.VISIBLE
                updateProgressBar.progress = state.progress.percent
                updateProgressValue.visibility = View.VISIBLE
                updateProgressValue.text = context.getString(
                    R.string.update_progress_percent,
                    state.progress.percent
                )
                installUpdateButton.visibility = View.GONE
                checkUpdateButton.isEnabled = false
            }

            UpdateUiState.Offline -> {
                updateStatusValue.setTextColor(negative)
                updateStatusValue.text = context.getString(R.string.update_status_offline)
                updateDetailValue.visibility = View.GONE
                updateProgressBar.visibility = View.GONE
                updateProgressValue.visibility = View.GONE
                installUpdateButton.visibility = View.GONE
                checkUpdateButton.isEnabled = true
            }

            UpdateUiState.Error -> {
                updateStatusValue.setTextColor(negative)
                updateStatusValue.text = context.getString(R.string.update_status_error)
                updateDetailValue.visibility = View.GONE
                updateProgressBar.visibility = View.GONE
                updateProgressValue.visibility = View.GONE
                installUpdateButton.visibility = View.GONE
                checkUpdateButton.isEnabled = true
            }
        }
    }

    private fun startProgressPolling() {
        mainHandler.removeCallbacks(progressPollRunnable)
        mainHandler.post(progressPollRunnable)
    }

    private fun stopProgressPolling() {
        mainHandler.removeCallbacks(progressPollRunnable)
    }

    private fun formatMegabytes(bytes: Long): String {
        if (bytes <= 0L) return "0.0MB"
        val value = bytes / (1024f * 1024f)
        return String.format("%.1fMB", value)
    }

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            when {
                value.resourceId != 0 -> ContextCompat.getColor(context, value.resourceId)
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
                else -> fallback
            }
        } else {
            fallback
        }
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()

    private sealed interface UpdateUiState {
        data object Idle : UpdateUiState
        data object Checking : UpdateUiState
        data object Offline : UpdateUiState
        data object Error : UpdateUiState
        data class UpToDate(val versionName: String) : UpdateUiState
        data class Outdated(val info: AppUpdateInfo) : UpdateUiState
        data class Downloading(val progress: AppUpdateDownloadProgress) : UpdateUiState
    }

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
