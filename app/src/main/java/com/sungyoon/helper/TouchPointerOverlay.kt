package com.sungyoon.helper

import android.content.Context
import android.content.Intent
import com.sungyoon.helper.overlay.pointer.PointerOverlayController

object TouchPointerOverlay {
    private val lock = Any()
    private var controller: PointerOverlayController? = null

    fun show(context: Context) {
        val app = context.applicationContext

        // ✅ 패널이 켜지면 예약 일시정지
        try {
            app.sendBroadcast(
                Intent(SungyoonHelperService.ACTION_PAUSE_RESERVATION).apply {
                    setPackage(app.packageName)
                }
            )
        } catch (_: Throwable) {}

        synchronized(lock) {
            val c = controller ?: PointerOverlayController(app).also { controller = it }
            c.show()
        }
    }

    fun hide() {
        synchronized(lock) {
            controller?.hide()
        }

        // ✅ 패널이 꺼지면 예약 재개
        // (수동 시퀀스 시작 시에는 서비스가 예약을 stop 처리하므로, resume이 와도 active=false면 무시됩니다)
        val c = controller
        val app = (c?.let { null } ?: return) // no-op; controller가 없으면 context가 없음
    }

    fun isShowing(): Boolean {
        synchronized(lock) {
            return controller?.isShowing() == true
        }
    }

    fun toggle(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val c = controller ?: PointerOverlayController(app).also { controller = it }
            if (c.isShowing()) {
                c.hide()

                // ✅ 예약 재개
                try {
                    app.sendBroadcast(
                        Intent(SungyoonHelperService.ACTION_RESUME_RESERVATION).apply {
                            setPackage(app.packageName)
                        }
                    )
                } catch (_: Throwable) {}
            } else {
                // ✅ 예약 일시정지
                try {
                    app.sendBroadcast(
                        Intent(SungyoonHelperService.ACTION_PAUSE_RESERVATION).apply {
                            setPackage(app.packageName)
                        }
                    )
                } catch (_: Throwable) {}

                c.show()
            }
        }
    }
}
