package com.sungyoon.helper.update

sealed interface AppUpdateCheckResult {
    data object Offline : AppUpdateCheckResult
    data object Error : AppUpdateCheckResult
    data class UpToDate(val info: AppUpdateInfo) : AppUpdateCheckResult
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateCheckResult
}
