package com.sungyoon.helper.update

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sungyoon.helper.BuildConfig
import com.sungyoon.helper.R
import com.sungyoon.helper.util.toast
import java.io.File

object AppUpdateManager {
    private const val PREFS_NAME = "app_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_FILE_NAME = "file_name"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_ASSET_SIZE_BYTES = "asset_size_bytes"
    private const val KEY_AWAITING_INSTALL_PERMISSION = "awaiting_install_permission"

    fun checkForUpdates(
        activity: Activity,
        onResult: (AppUpdateCheckResult) -> Unit
    ) {
        if (!isNetworkAvailable(activity)) {
            onResult(AppUpdateCheckResult.Offline)
            return
        }

        Thread {
            val info = AppUpdateChecker.fetchLatestRelease()
            activity.runOnUiThread {
                when {
                    info == null -> onResult(AppUpdateCheckResult.Error)
                    info.versionCode > BuildConfig.DEV_VERSION_CODE ->
                        onResult(AppUpdateCheckResult.UpdateAvailable(info))
                    else -> onResult(AppUpdateCheckResult.UpToDate(info))
                }
            }
        }.start()
    }

    fun enqueueDownload(context: Context, info: AppUpdateInfo) {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val fileName = buildFileName(info)
        File(downloadsDir, fileName).delete()

        val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
            setTitle(context.getString(R.string.update_download_title))
            setDescription(
                context.getString(
                    R.string.update_download_description,
                    info.versionName
                )
            )
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val downloadId = manager.enqueue(request)
        prefs(context).edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_FILE_NAME, fileName)
            .putInt(KEY_VERSION_CODE, info.versionCode)
            .putLong(KEY_ASSET_SIZE_BYTES, info.assetSizeBytes)
            .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
            .apply()
        toast(context, context.getString(R.string.update_download_started))
    }

    fun handleDownloadCompleted(context: Context, downloadId: Long) {
        val prefs = prefs(context)
        val expectedId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId != expectedId || expectedId < 0L) return

        val fileName = prefs.getString(KEY_FILE_NAME, null) ?: return
        prefs.edit().putLong(KEY_DOWNLOAD_ID, -1L).apply()

        if (!isDownloadSuccessful(context, downloadId)) {
            toast(context, context.getString(R.string.update_download_failed))
            return
        }

        if (launchInstallIfPossible(context, fileName)) {
            prefs.edit().putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false).apply()
        } else {
            prefs.edit().putBoolean(KEY_AWAITING_INSTALL_PERMISSION, true).apply()
        }
    }

    fun resumePendingInstallIfNeeded(activity: Activity) {
        val prefs = prefs(activity)
        val waitingPermission = prefs.getBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
        val fileName = prefs.getString(KEY_FILE_NAME, null) ?: return
        if (!waitingPermission) return
        if (!canRequestPackageInstalls(activity)) return
        if (launchInstall(activity, fileName)) {
            prefs.edit().putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false).apply()
        }
    }

    fun isAwaitingInstallPermission(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AWAITING_INSTALL_PERMISSION, false) &&
            !canRequestPackageInstalls(context)
    }

    fun openInstallPermissionSettings(context: Context) {
        openUnknownSourcesSettings(context)
    }

    fun getDownloadProgress(context: Context): AppUpdateDownloadProgress? {
        val downloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId < 0L) return null

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = manager.query(query) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null

            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_PENDING &&
                status != DownloadManager.STATUS_PAUSED &&
                status != DownloadManager.STATUS_RUNNING &&
                status != DownloadManager.STATUS_SUCCESSFUL
            ) {
                return null
            }

            val downloadedBytes = it.getLongCompat(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val expectedBytes = prefs(context).getLong(KEY_ASSET_SIZE_BYTES, -1L)
            val managerTotalBytes = it.getLongCompat(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val totalBytes = maxOf(managerTotalBytes, expectedBytes).coerceAtLeast(0L)
            val reason = it.getIntCompat(DownloadManager.COLUMN_REASON)
            val normalizedDownloadedBytes = when {
                status == DownloadManager.STATUS_SUCCESSFUL && totalBytes > 0L -> totalBytes
                else -> downloadedBytes.coerceAtLeast(0L)
            }
            val percent = if (normalizedDownloadedBytes <= 0L || totalBytes <= 0L) {
                0
            } else {
                ((normalizedDownloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            }

            return AppUpdateDownloadProgress(
                percent = percent,
                downloadedBytes = normalizedDownloadedBytes,
                totalBytes = totalBytes,
                status = status,
                reason = reason,
            )
        }
    }

    private fun isDownloadSuccessful(context: Context, downloadId: Long): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = manager.query(query) ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    private fun launchInstallIfPossible(context: Context, fileName: String): Boolean {
        if (!canRequestPackageInstalls(context)) return false
        return launchInstall(context, fileName)
    }

    private fun launchInstall(context: Context, fileName: String): Boolean {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        val apkFile = File(downloadsDir, fileName)
        if (!apkFile.exists()) {
            toast(context, context.getString(R.string.update_install_file_missing))
            return false
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            toast(context, context.getString(R.string.update_install_failed))
            false
        } catch (_: Throwable) {
            toast(context, context.getString(R.string.update_install_failed))
            false
        }
    }

    private fun openUnknownSourcesSettings(context: Context) {
        toast(context, context.getString(R.string.update_install_permission_required))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            toast(context, context.getString(R.string.update_install_permission_open_failed))
        }
    }

    private fun canRequestPackageInstalls(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildFileName(info: AppUpdateInfo): String {
        val versionName = info.versionName
            .replace(Regex("""[\\/:*?"<>|]"""), "")
            .trim()
            .ifBlank { "최신" }
        return "성윤 터치매크로 v$versionName.apk"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Cursor.getLongCompat(columnName: String): Long {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return -1L
        return getLong(index)
    }

    private fun Cursor.getIntCompat(columnName: String): Int {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return 0
        return getInt(index)
    }
}
