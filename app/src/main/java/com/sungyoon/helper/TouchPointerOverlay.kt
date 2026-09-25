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
    }

    suspend fun flushEdits() { controller?.flushEdits() }
    suspend fun flushAndHide() { controller?.flushAndHide() }

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

                c.show(forceOpenControlPanel = true)
            }
        }
    }
}
