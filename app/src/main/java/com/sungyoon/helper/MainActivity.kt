package com.sungyoon.helper

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import com.sungyoon.helper.update.AppUpdateManager
import com.sungyoon.helper.feedback.FeedbackRuntime


class MainActivity : ComponentActivity() {

    private var mainView: MainScreenView? = null

    companion object {
        private var didInitialUpdateCheck = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdateManager.clearInstalledUpdate(this)
        FeedbackRuntime.recover(this)

        val v = MainScreenView(this)
        mainView = v
        setContentView(v)

        if (!didInitialUpdateCheck) {
            didInitialUpdateCheck = true
            v.post {
                mainView?.performInitialUpdateCheckIfNeeded()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainView?.refreshPermissionStateAndMaybeNavigate()
        AppUpdateManager.resumePendingInstallIfNeeded(this)
        mainView?.refreshUpdateProgress()


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
}
