package com.sungyoon.helper.util

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

private val main = Handler(Looper.getMainLooper())

private fun isAppForeground(ctx: Context): Boolean {
    return try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pkg = ctx.packageName
        val procs = am.runningAppProcesses ?: return false
        procs.any {
            it.processName == pkg && (
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                    )
        }
    } catch (_: Throwable) {
        false
    }
}

fun toast(context: Context, msg: String) {
            OverlayToast.show(context, msg)
}
