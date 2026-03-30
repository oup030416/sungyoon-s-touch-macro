package com.sungyoon.helper

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import com.sungyoon.helper.update.AppUpdateManager


class MainActivity : ComponentActivity() {

    private var mainView: MainScreenView? = null

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
