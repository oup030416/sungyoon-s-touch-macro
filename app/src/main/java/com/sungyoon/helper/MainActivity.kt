package com.sungyoon.helper

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import com.sungyoon.helper.core.permissions.isOverlayGranted
import com.sungyoon.helper.core.permissions.isServiceEnabled
import com.sungyoon.helper.update.AppUpdateManager


class MainActivity : ComponentActivity() {

    private var mainView: MainScreenView? = null
    private var updateCheckStarted = false
    private var promptedUpdateVersionCode: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val v = MainScreenView(this)
        mainView = v
        setContentView(v)
    }

    override fun onResume() {
        super.onResume()
        mainView?.refreshPermissionStateAndMaybeNavigate()
        AppUpdateManager.resumePendingInstallIfNeeded(this)
        maybeCheckForUpdates()


        // ✅ 앱 실행/복귀 시 플로팅 버튼 다시 띄우기 요청
        sendBroadcast(
            Intent(SungyoonHelperService.ACTION_ENSURE_FLOATING_TOGGLE).apply {
                setPackage(packageName)
            }
        )
    }

    override fun onDestroy() {
        TouchPointerOverlay.hide()
        mainView = null
        super.onDestroy()
    }

    private fun maybeCheckForUpdates() {
        if (updateCheckStarted) return
        if (!isOverlayGranted(this) || !isServiceEnabled(this)) return

        updateCheckStarted = true
        AppUpdateManager.checkForUpdates(this) { info ->
            updateCheckStarted = false
            val next = info ?: return@checkForUpdates
            if (promptedUpdateVersionCode == next.versionCode) return@checkForUpdates
            promptedUpdateVersionCode = next.versionCode
            AppUpdateManager.showUpdateDialog(this, next)
        }
    }
}
